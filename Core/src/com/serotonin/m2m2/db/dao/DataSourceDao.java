/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.db.dao;

import java.io.InputStream;
import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infiniteautomation.mango.spring.MangoRuntimeContextConfiguration;
import com.infiniteautomation.mango.spring.db.DataPointTableDefinition;
import com.infiniteautomation.mango.spring.db.DataSourceTableDefinition;
import com.infiniteautomation.mango.spring.db.EventHandlerTableDefinition;
import com.infiniteautomation.mango.spring.db.RoleTableDefinition;
import com.infiniteautomation.mango.spring.events.DaoEventType;
import com.infiniteautomation.mango.spring.service.PermissionService;
import com.infiniteautomation.mango.util.LazyInitSupplier;
import com.infiniteautomation.mango.util.usage.DataSourceUsageStatistics;
import com.serotonin.ModuleNotLoadedException;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.rt.event.type.AuditEventType;
import com.serotonin.m2m2.rt.event.type.EventType;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.util.SerializationHelper;

@Repository()
public class DataSourceDao extends AbstractDao<DataSourceVO, DataSourceTableDefinition> {

    //TODO Clean up/remove
    public static final Name DATA_SOURCES_ALIAS = DSL.name("ds");
    public static final Table<Record> DATA_SOURCES = DSL.table(DSL.name(SchemaDefinition.DATASOURCES_TABLE)).as(DATA_SOURCES_ALIAS);
    public static final Field<Integer> ID = DSL.field(DATA_SOURCES_ALIAS.append("id"), SQLDataType.INTEGER.nullable(false));
    public static final Field<String> EDIT_PERMISSION = DSL.field(DATA_SOURCES_ALIAS.append("editPermission"), SQLDataType.VARCHAR(255).nullable(true));

    static final Log LOG = LogFactory.getLog(DataSourceDao.class);
    private static final String DATA_SOURCE_SELECT = //
            "SELECT id, xid, name, dataSourceType, data FROM dataSources ";

    private final DataPointTableDefinition dataPointTable;

    private static final LazyInitSupplier<DataSourceDao> springInstance = new LazyInitSupplier<>(() -> {
        Object o = Common.getRuntimeContext().getBean(DataSourceDao.class);
        if(o == null)
            throw new ShouldNeverHappenException("DAO not initialized in Spring Runtime Context");
        return (DataSourceDao)o;
    });

    @Autowired
    private DataSourceDao(
            DataSourceTableDefinition table,
            DataPointTableDefinition dataPointTable,
            @Qualifier(MangoRuntimeContextConfiguration.DAO_OBJECT_MAPPER_NAME)ObjectMapper mapper,
            ApplicationEventPublisher publisher) {
        super(AuditEventType.TYPE_DATA_SOURCE, table,
                new TranslatableMessage("internal.monitor.DATA_SOURCE_COUNT"),
                mapper, publisher);
        this.dataPointTable = dataPointTable;
    }

    /**
     * Get cached instance from Spring Context
     * @return
     */
    public static DataSourceDao getInstance() {
        return springInstance.get();
    }

    /**
     * Get all data sources for a given type
     * @param type
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T extends DataSourceVO> List<T> getDataSourcesForType(String type) {
        return (List<T>) query(DATA_SOURCE_SELECT + "WHERE dataSourceType=?", new Object[] { type }, getListResultSetExtractor());
    }

    @Override
    public boolean delete(DataSourceVO vo) {
        //Since we are going to delete all the points we will select them for update as well as the data source
        if (vo != null) {
            DataSourceDeletionResult result = new DataSourceDeletionResult();
            int tries = transactionRetries;
            while(tries > 0) {
                try {
                    withLockedRow(vo.getId(), (txStatus) -> {
                        result.points = DataPointDao.getInstance().deleteDataPoints(vo.getId());
                        deleteRelationalData(vo);
                        result.deleted = this.create.deleteFrom(this.table.getTable()).where(this.table.getIdField().eq(vo.getId())).execute();
                    });
                    break;
                }catch(org.jooq.exception.DataAccessException | ConcurrencyFailureException e) {
                    if(tries == 1) {
                        throw e;
                    }
                }
                tries--;
            }

            if(this.countMonitor != null) {
                this.countMonitor.addValue(-result.deleted);
            }

            if(result.deleted > 0) {
                this.publishEvent(createDaoEvent(DaoEventType.DELETE, vo, null));
                AuditEventType.raiseDeletedEvent(this.typeName, vo);
            }

            DataPointDao.getInstance().raiseDeletedEvents(result.points);

            return result.deleted > 0;
        }
        return false;
    }

    private class DataSourceDeletionResult {
        private List<DataPointVO> points;
        private Integer deleted;
    }

    /**
     * Delete all data source for a given type
     *  used during module uninstall
     * @param dataSourceType
     */
    public void deleteDataSourceType(final String dataSourceType) {
        List<DataSourceVO> dss = getDataSourcesForType(dataSourceType);
        for(DataSourceVO ds : dss) {
            delete(ds);
        }
    }

    /**
     * Get runtime data
     * @param id
     * @return
     */
    public Object getPersistentData(int id) {
        return query("select rtdata from dataSources where id=?", new Object[] { id },
                new ResultSetExtractor<Serializable>() {
            @Override
            public Serializable extractData(ResultSet rs) throws SQLException, DataAccessException {
                if (!rs.next())
                    return null;

                InputStream in = rs.getBinaryStream(1);
                if (in == null)
                    return null;

                return (Serializable) SerializationHelper.readObjectInContext(in);
            }
        });
    }

    /**
     * Save runtime data
     * @param id
     * @param data
     */
    public void savePersistentData(int id, Object data) {
        ejt.update("UPDATE dataSources SET rtdata=? WHERE id=?", new Object[] { SerializationHelper.writeObject(data),
                id }, new int[] { Types.BINARY, Types.INTEGER });
    }

    /**
     * Get the count of data sources per type
     * @return
     */
    public List<DataSourceUsageStatistics> getUsage() {
        return ejt.query("SELECT dataSourceType, COUNT(dataSourceType) FROM dataSources GROUP BY dataSourceType", new RowMapper<DataSourceUsageStatistics>() {
            @Override
            public DataSourceUsageStatistics mapRow(ResultSet rs, int rowNum) throws SQLException {
                DataSourceUsageStatistics usage = new DataSourceUsageStatistics();
                usage.setDataSourceType(rs.getString(1));
                usage.setCount(rs.getInt(2));
                return usage;
            }
        });
    }

    class DataSourceRowMapper implements RowMapper<DataSourceVO> {
        @Override
        public DataSourceVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            DataSourceVO ds = (DataSourceVO) SerializationHelper.readObjectInContext(rs.getBinaryStream(5));
            ds.setId(rs.getInt(1));
            ds.setXid(rs.getString(2));
            ds.setName(rs.getString(3));
            ds.setDefinition(ModuleRegistry.getDataSourceDefinition(rs.getString(4)));
            return ds;
        }
    }

    @Override
    protected ResultSetExtractor<List<DataSourceVO>> getListResultSetExtractor() {
        return getListResultSetExtractor((e, rs) -> {
            if (e.getCause() instanceof ModuleNotLoadedException) {
                try {
                    LOG.error( "Data source with type '" +
                            rs.getString("dataSourceType") +
                            "' and xid '" + rs.getString("xid") +
                            "' could not be loaded. Is its module missing?", e.getCause());
                }catch(SQLException e1) {
                    LOG.error(e.getMessage(), e);
                }
            }else {
                LOG.error(e.getMessage(), e);
            }
        });
    }

    @Override
    protected ResultSetExtractor<Void> getCallbackResultSetExtractor(
            MappedRowCallback<DataSourceVO> callback) {
        return getCallbackResultSetExtractor(callback, (e, rs) -> {
            if (e.getCause() instanceof ModuleNotLoadedException) {
                try {
                    LOG.error( "Data source with type '" +
                            rs.getString("dataSourceType") +
                            "' and xid '" + rs.getString("xid") +
                            "' could not be loaded. Is its module missing?", e.getCause());
                }catch(SQLException e1) {
                    LOG.error(e.getMessage(), e);
                }
            }else {
                LOG.error(e.getMessage(), e);
            }
        });
    }

    @Override
    protected String getXidPrefix() {
        return DataSourceVO.XID_PREFIX;
    }

    @Override
    protected Object[] voToObjectArray(DataSourceVO vo) {
        return new Object[] {
                vo.getXid(),
                vo.getName(),
                vo.getDefinition().getDataSourceTypeName(),
                SerializationHelper.writeObjectToArray(vo)};
    }

    @Override
    public RowMapper<DataSourceVO> getRowMapper() {
        return new DataSourceRowMapper();
    }

    @Override
    public void loadRelationalData(DataSourceVO vo) {
        vo.setEditRoles(RoleDao.getInstance().getRoles(vo.getId(), DataSourceVO.class.getSimpleName(), PermissionService.EDIT));
        vo.getDefinition().loadRelationalData(vo);
    }

    @Override
    public void saveRelationalData(DataSourceVO vo, boolean insert) {
        RoleDao.getInstance().replaceRolesOnVoPermission(vo.getEditRoles(), vo.getId(), DataSourceVO.class.getSimpleName(), PermissionService.EDIT, insert);
        vo.getDefinition().saveRelationalData(vo, insert);
    }

    @Override
    public void deleteRelationalData(DataSourceVO vo) {
        create.deleteFrom(EventHandlerTableDefinition.EVENT_HANDLER_MAPPING_TABLE)
        .where(EventHandlerTableDefinition.EVENT_HANDLER_MAPPING_EVENT_TYPE_NAME.eq(EventType.EventTypeNames.DATA_SOURCE),
                EventHandlerTableDefinition.EVENT_HANDLER_MAPPING_TYPEREF1.eq(vo.getId())).execute();

        RoleDao.getInstance().deleteRolesForVoPermission(vo.getId(), DataSourceVO.class.getSimpleName(), PermissionService.EDIT);
        vo.getDefinition().deleteRelationalData(vo);
    }

    @Override
    public Condition hasPermission(PermissionHolder user, String permissionType) {
        List<Integer> roleIds = user.getRoles().stream().map(r -> r.getId()).collect(Collectors.toList());
        Condition roleIdsIn = RoleTableDefinition.roleIdFieldAlias.in(roleIds);
        if(PermissionService.EDIT.equals(permissionType) || PermissionService.READ.equals(permissionType)) {
            Condition readCondition = DSL.and(
                    RoleTableDefinition.voTypeFieldAlias.eq(DataSourceVO.class.getSimpleName()),
                    RoleTableDefinition.voIdFieldAlias.eq(this.table.getIdAlias()),
                    RoleTableDefinition.permissionTypeFieldAlias.eq(PermissionService.EDIT)
                    );
            return this.table.getIdAlias().in(this.create.selectDistinct(RoleTableDefinition.voIdFieldAlias)
                    .from(RoleTableDefinition.roleMappingTableAsAlias)
                    .where(readCondition, roleIdsIn));
        }else {
            return DSL.falseCondition();
        }
    }
}
