/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.db.dao;

import static com.serotonin.m2m2.db.dao.DataPointTagsDao.DATA_POINT_TAGS_PIVOT_ALIAS;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.SelectOnConditionStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.infiniteautomation.mango.db.query.ConditionSortLimit;
import com.infiniteautomation.mango.db.query.ConditionSortLimitWithTagKeys;
import com.infiniteautomation.mango.db.query.Index;
import com.infiniteautomation.mango.db.query.JoinClause;
import com.infiniteautomation.mango.db.query.QueryAttribute;
import com.infiniteautomation.mango.db.query.RQLToCondition;
import com.infiniteautomation.mango.db.query.RQLToConditionWithTagKeys;
import com.infiniteautomation.mango.spring.events.DaoEvent;
import com.infiniteautomation.mango.spring.events.DaoEventType;
import com.infiniteautomation.mango.spring.events.DataPointTagsUpdatedEvent;
import com.infiniteautomation.mango.spring.service.PermissionService;
import com.infiniteautomation.mango.util.LazyInitSupplier;
import com.infiniteautomation.mango.util.usage.DataPointUsageStatistics;
import com.serotonin.ModuleNotLoadedException;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.db.pair.IntStringPair;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.DataTypes;
import com.serotonin.m2m2.IMangoLifecycle;
import com.serotonin.m2m2.LicenseViolatedException;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.DataPointChangeDefinition;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.rt.event.type.AuditEventType;
import com.serotonin.m2m2.rt.event.type.EventType;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.bean.PointHistoryCount;
import com.serotonin.m2m2.vo.comment.UserCommentVO;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.event.detector.AbstractEventDetectorVO;
import com.serotonin.m2m2.vo.event.detector.AbstractPointEventDetectorVO;
import com.serotonin.provider.Providers;
import com.serotonin.util.SerializationHelper;

import net.jazdw.rql.parser.ASTNode;

/**
 * This class is a Half-Breed between the legacy Dao and the new type that extends AbstractDao.
 *
 * The top half of the code is the legacy code, the bottom is the new style.
 *
 * Eventually all the method innards will be reworked, leaving the names the same.
 *
 * @author Terry Packer
 *
 */
@Repository()
public class DataPointDao extends AbstractDao<DataPointVO>{
    static final Log LOG = LogFactory.getLog(DataPointDao.class);

    private static final LazyInitSupplier<DataPointDao> springInstance = new LazyInitSupplier<>(() -> {
        Object o = Common.getRuntimeContext().getBean(DataPointDao.class);
        if(o == null)
            throw new ShouldNeverHappenException("DAO not initialized in Spring Runtime Context");
        return (DataPointDao)o;
    });

    public static final Name DATA_POINTS_ALIAS = DSL.name("dp");
    public static final Table<Record> DATA_POINTS = DSL.table(DSL.name(SchemaDefinition.DATAPOINTS_TABLE)).as(DATA_POINTS_ALIAS);
    public static final Field<Integer> ID = DSL.field(DATA_POINTS_ALIAS.append("id"), SQLDataType.INTEGER.nullable(false));
    public static final Field<Integer> DATA_SOURCE_ID = DSL.field(DATA_POINTS_ALIAS.append("dataSourceId"), SQLDataType.INTEGER.nullable(false));
    public static final Field<String> READ_PERMISSION = DSL.field(DATA_POINTS_ALIAS.append("readPermission"), SQLDataType.VARCHAR(255).nullable(true));
    public static final Field<String> SET_PERMISSION = DSL.field(DATA_POINTS_ALIAS.append("setPermission"), SQLDataType.VARCHAR(255).nullable(true));

    /**
     * Private as we only ever want 1 of these guys
     */
    private DataPointDao() {
        super(EventType.EventTypeNames.DATA_POINT, "dp",
                new String[] { "ds.name", "ds.xid", "ds.dataSourceType" }, //Extra Properties not in table
                false, new TranslatableMessage("internal.monitor.DATA_POINT_COUNT"));
    }

    /**
     * Get cached instance from Spring Context
     * @return
     */
    public static DataPointDao getInstance() {
        return springInstance.get();
    }

    //
    //
    // Data Points
    //
    /**
     * Get data points for a data source
     * @param dataSourceId
     * @param includeRelationalData
     * @return
     */
    public List<DataPointVO> getDataPoints(int dataSourceId, boolean includeRelationalData) {
        List<DataPointVO> dps = query(SELECT_ALL + " where dp.dataSourceId=?", new Object[] { dataSourceId },
                new DataPointRowMapper());
        if (includeRelationalData)
            loadPartialRelationalData(dps);
        return dps;
    }

    public List<DataPointVO> getDataPointsForDataSourceStart(int dataSourceId) {
        List<DataPointVO> dps = query(DataPointStartupResultSetExtractor.DATA_POINT_SELECT_STARTUP, new Object[] { dataSourceId },
                new DataPointStartupResultSetExtractor());

        return dps;
    }

    /**
     * Get all data point Ids in the table
     * @return
     */
    public List<Integer> getDataPointIds(){
        return queryForList("SELECT id FROM dataPoints" , Integer.class);
    }

    class DataPointStartupResultSetExtractor implements ResultSetExtractor<List<DataPointVO>> {
        private static final int EVENT_DETECTOR_FIRST_COLUMN = 28;
        private final EventDetectorRowMapper<?> eventRowMapper = new EventDetectorRowMapper<>(EVENT_DETECTOR_FIRST_COLUMN, 5);
        static final String DATA_POINT_SELECT_STARTUP = //
                "select dp.data, dp.id, dp.xid, dp.dataSourceId, dp.name, dp.deviceName, dp.enabled, " //
                + "  dp.loggingType, dp.intervalLoggingPeriodType, dp.intervalLoggingPeriod, dp.intervalLoggingType, " //
                + "  dp.tolerance, dp.purgeOverride, dp.purgeType, dp.purgePeriod, dp.defaultCacheSize, " //
                + "  dp.discardExtremeValues, dp.engineeringUnits, dp.readPermission, dp.setPermission, dp.rollup, ds.name, " //
                + "  ds.xid, ds.dataSourceType, ds.editPermission, ped.id, ped.xid, ped.sourceTypeName, ped.typeName, ped.data, ped.dataPointId " //
                + "  from dataPoints dp join dataSources ds on ds.id = dp.dataSourceId " //
                + "  left outer join eventDetectors ped on dp.id = ped.dataPointId where dp.dataSourceId=?";

        @Override
        public List<DataPointVO> extractData(ResultSet rs) throws SQLException, DataAccessException {
            Map<Integer, DataPointVO> result = new HashMap<Integer, DataPointVO>();
            DataPointRowMapper pointRowMapper = new DataPointRowMapper();
            while(rs.next()) {
                int id = rs.getInt(2); //dp.id column number
                if(result.containsKey(id))
                    try{
                        addEventDetector(result.get(id), rs);
                    }catch(Exception e){
                        LOG.error("Point not fully initialized: " + e.getMessage(), e);
                    }
                else {
                    DataPointVO dpvo = pointRowMapper.mapRow(rs, rs.getRow());
                    dpvo.setEventDetectors(new ArrayList<AbstractPointEventDetectorVO<?>>());
                    result.put(id, dpvo);
                    try{
                        addEventDetector(dpvo, rs);
                    }catch(Exception e){
                        LOG.error("Point not fully initialized: " + e.getMessage(), e);
                    }
                }
            }
            return new ArrayList<DataPointVO>(result.values());
        }

        private void addEventDetector(DataPointVO dpvo, ResultSet rs) throws SQLException {
            if(rs.getObject(EVENT_DETECTOR_FIRST_COLUMN) == null)
                return;
            AbstractEventDetectorVO<?> edvo = eventRowMapper.mapRow(rs, rs.getRow());
            AbstractPointEventDetectorVO<?> ped = (AbstractPointEventDetectorVO<?>) edvo;
            dpvo.getEventDetectors().add(ped);
        }
    }

    public static class DataPointRowMapper implements RowMapper<DataPointVO> {
        @Override
        public DataPointVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            int i = 0;

            DataPointVO dp = (DataPointVO) SerializationHelper.readObjectInContext(rs.getBinaryStream(++i));
            dp.setId(rs.getInt(++i));
            dp.setXid(rs.getString(++i));
            dp.setDataSourceId(rs.getInt(++i));
            dp.setName(rs.getString(++i));
            dp.setDeviceName(rs.getString(++i));
            dp.setEnabled(charToBool(rs.getString(++i)));
            dp.setLoggingType(rs.getInt(++i));
            dp.setIntervalLoggingPeriodType(rs.getInt(++i));
            dp.setIntervalLoggingPeriod(rs.getInt(++i));
            dp.setIntervalLoggingType(rs.getInt(++i));
            dp.setTolerance(rs.getDouble(++i));
            dp.setPurgeOverride(charToBool(rs.getString(++i)));
            dp.setPurgeType(rs.getInt(++i));
            dp.setPurgePeriod(rs.getInt(++i));
            dp.setDefaultCacheSize(rs.getInt(++i));
            dp.setDiscardExtremeValues(charToBool(rs.getString(++i)));
            dp.setEngineeringUnits(rs.getInt(++i));
            dp.setRollup(rs.getInt(++i));

            // Data source information.
            dp.setDataSourceName(rs.getString(++i));
            dp.setDataSourceXid(rs.getString(++i));
            dp.setDataSourceTypeName(rs.getString(++i));
            String dsEditRoles = rs.getString(++i);
            //TODO JOIN on roles table and set dsEditRoles

            dp.ensureUnitsCorrect();

            return dp;
        }
    }
    
    /**
     * Check licensing before adding a point
     */
    private void checkAddPoint() {
        IMangoLifecycle lifecycle = Providers.get(IMangoLifecycle.class);
        Integer limit = lifecycle.dataPointLimit();
        if(limit != null && this.countMonitor.getValue() >= limit) {
            String licenseType;
            if(Common.license() != null)
                licenseType = Common.license().getLicenseType();
            else
                licenseType = "Free";
            throw new LicenseViolatedException(new TranslatableMessage("license.dataPointLimit", licenseType, limit));
        }
    }

    @Override
    public void insert(DataPointVO vo, boolean full) {
        checkAddPoint();
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.beforeInsert(vo);

        // Create a default text renderer
        if (vo.getTextRenderer() == null)
            vo.defaultTextRenderer();
        
        super.insert(vo, full);
        
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.afterInsert(vo);
    }
    
    @Override
    public void update(DataPointVO existing, DataPointVO vo, boolean full) {
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.beforeUpdate(vo);
        
        //If have a new data type we will wipe our history
        if (existing.getPointLocator().getDataTypeId() != vo.getPointLocator().getDataTypeId())
            Common.databaseProxy.newPointValueDao().deletePointValues(vo.getId());

        super.update(existing, vo, full);
        
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.afterUpdate(vo);
    }
    
    /**
     * Update the enabled column, should only be done via the runtime manager
     * @param dp
     */
    public void saveEnabledColumn(DataPointVO dp) {
        ejt.update("UPDATE dataPoints SET enabled=? WHERE id=?", new Object[]{boolToChar(dp.isEnabled()), dp.getId()});
        this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.UPDATE, dp, null));
        AuditEventType.raiseToggleEvent(AuditEventType.TYPE_DATA_POINT, dp);
    }

    /**
     * Is a data point enabled, returns false if point is disabled or DNE.
     * @param id
     * @return
     */
    public boolean isEnabled(int id) {
        return query("select dp.enabled from dataPoints as dp WHERE id=?", new Object[] {id}, new ResultSetExtractor<Boolean>() {

            @Override
            public Boolean extractData(ResultSet rs) throws SQLException, DataAccessException {
                if(rs.next()) {
                    return charToBool(rs.getString(1));
                }else
                    return false;
            }

        });
    }

    @Override
    public boolean delete(DataPointVO vo) {
        boolean deleted = false;
        if (vo != null) {
            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.beforeDelete(vo.getId());
            deleted = super.delete(vo);
            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.afterDelete(vo.getId());
        }
        return deleted;
    }
    
    @Override
    public void deleteRelationalData(DataPointVO vo) {
        ejt.update("delete from eventHandlersMapping where eventTypeName=? and eventTypeRef1 = " + vo.getId(),
                new Object[] { EventType.EventTypeNames.DATA_POINT });
        ejt.update("delete from userComments where commentType=2 and typeKey = " + vo.getId());
        ejt.update("delete from eventDetectors where dataPointId = " + vo.getId());
        ejt.update("delete from dataPoints where id = " + vo.getId());
        RoleDao.getInstance().deleteRolesForVoPermission(vo, PermissionService.READ);
        RoleDao.getInstance().deleteRolesForVoPermission(vo, PermissionService.SET);
    }

    /**
     * Count the data points on a data source, used for licensing
     * @param dataSourceType
     * @return
     */
    public int countPointsForDataSourceType(String dataSourceType) {
        return ejt.queryForInt("SELECT count(DISTINCT dp.id) FROM dataPoints dp LEFT JOIN dataSources ds ON dp.dataSourceId=ds.id "
                + "WHERE ds.dataSourceType=?", new Object[] { dataSourceType }, 0);
    }

    //
    //
    // Event detectors
    //

    /**
     * 
     * Loads the event detectors from the database and sets them on the data point.
     *
     * @param dp
     */
    public void loadEventDetectors(DataPointVO dp) {
        dp.setEventDetectors(EventDetectorDao.getInstance().getWithSource(dp.getId(), dp));
    }

    private void saveEventDetectors(DataPointVO dp) {
        // Get the ids of the existing detectors for this point.
        final List<AbstractPointEventDetectorVO<?>> existingDetectors = EventDetectorDao.getInstance().getWithSource(dp.getId(), dp);

        // Insert or update each detector in the point.
        for (AbstractPointEventDetectorVO<?> ped : dp.getEventDetectors()) {
            ped.setSourceId(dp.getId());
            if (ped.getId() > 0){
                //Remove from list
                AbstractPointEventDetectorVO<?> existing = removeFromList(existingDetectors, ped.getId());
                EventDetectorDao.getInstance().update(existing, ped, true);
            } else {
                ped.setId(Common.NEW_ID);
                EventDetectorDao.getInstance().insert(ped, true);
            }
        }

        // Delete detectors for any remaining ids in the list of existing
        // detectors.
        for (AbstractEventDetectorVO<?> ed : existingDetectors) {
            EventDetectorDao.getInstance().delete(ed);
        }
    }

    private AbstractPointEventDetectorVO<?> removeFromList(List<AbstractPointEventDetectorVO<?>> list, int id) {
        for (AbstractPointEventDetectorVO<?> ped : list) {
            if (ped.getId() == id) {
                list.remove(ped);
                return ped;
            }
        }
        return null;
    }

    //
    //
    // Point comments
    //
    private static final String POINT_COMMENT_SELECT = UserCommentDao.USER_COMMENT_SELECT
            + "where uc.commentType= " + UserCommentVO.TYPE_POINT + " and uc.typeKey=? " + "order by uc.ts";

    /**
     * Loads the comments from the database and them on the data point.
     *
     * @param dp
     */
    private void loadPointComments(DataPointVO dp) {
        dp.setComments(query(POINT_COMMENT_SELECT, new Object[] { dp.getId() }, UserCommentDao.getInstance().getRowMapper()));
    }


    /**
     * Get the count of all point values for all points
     *
     * @return
     */
    public List<PointHistoryCount> getTopPointHistoryCounts() {
        if (Common.databaseProxy.getNoSQLProxy() == null)
            return this.getTopPointHistoryCountsSql();
        return this.getTopPointHistoryCountsNoSql();
    }

    /**
     * NoSQL version to count point values for each point
     * @return
     */
    private List<PointHistoryCount> getTopPointHistoryCountsNoSql() {

        PointValueDao dao = Common.databaseProxy.newPointValueDao();
        //For now we will do this the slow way
        List<DataPointVO> points = query(SELECT_ALL + " ORDER BY deviceName, name", getListResultSetExtractor());
        List<PointHistoryCount> counts = new ArrayList<>();
        for (DataPointVO point : points) {
            PointHistoryCount phc = new PointHistoryCount();
            long count = dao.dateRangeCount(point.getId(), 0L, Long.MAX_VALUE);
            phc.setCount((int) count);
            phc.setPointId(point.getId());
            phc.setPointName(point.getName());
            counts.add(phc);
        }
        Collections.sort(counts, new Comparator<PointHistoryCount>() {

            @Override
            public int compare(PointHistoryCount count1, PointHistoryCount count2) {
                return count2.getCount() - count1.getCount();
            }

        });

        return counts;
    }

    /**
     * SQL version to count point values for each point
     * @return
     */
    private List<PointHistoryCount> getTopPointHistoryCountsSql() {
        List<PointHistoryCount> counts = query(
                "select dataPointId, count(*) from pointValues group by dataPointId order by 2 desc",
                new RowMapper<PointHistoryCount>() {
                    @Override
                    public PointHistoryCount mapRow(ResultSet rs, int rowNum) throws SQLException {
                        PointHistoryCount c = new PointHistoryCount();
                        c.setPointId(rs.getInt(1));
                        c.setCount(rs.getInt(2));
                        return c;
                    }
                });

        List<DataPointVO> points = query(SELECT_ALL + " ORDER BY deviceName, name", getListResultSetExtractor());

        // Collate in the point names.
        for (PointHistoryCount c : counts) {
            for (DataPointVO point : points) {
                if (point.getId() == c.getPointId()) {
                    c.setPointName(point.getExtendedName());
                    break;
                }
            }
        }

        // Remove the counts for which there are no point, i.e. deleted.
        Iterator<PointHistoryCount> iter = counts.iterator();
        while (iter.hasNext()) {
            PointHistoryCount c = iter.next();
            if (c.getPointName() == null)
                iter.remove();
        }

        return counts;
    }

    /**
     * Get the count of data points per type of data source
     * @return
     */
    public List<DataPointUsageStatistics> getUsage() {
        return ejt.query("SELECT ds.dataSourceType, COUNT(ds.dataSourceType) FROM dataPoints as dp LEFT JOIN dataSources ds ON dp.dataSourceId=ds.id GROUP BY ds.dataSourceType",
                new RowMapper<DataPointUsageStatistics>() {
            @Override
            public DataPointUsageStatistics mapRow(ResultSet rs, int rowNum) throws SQLException {
                DataPointUsageStatistics usage = new DataPointUsageStatistics();
                usage.setDataSourceType(rs.getString(1));
                usage.setCount(rs.getInt(2));
                return usage;
            }
        });
    }

    @Override
    protected String getTableName() {
        return SchemaDefinition.DATAPOINTS_TABLE;
    }

    @Override
    protected String getXidPrefix() {
        return DataPointVO.XID_PREFIX;
    }

    @Override
    protected Object[] voToObjectArray(DataPointVO vo) {
        return new Object[] { SerializationHelper.writeObject(vo), vo.getXid(), vo.getDataSourceId(), vo.getName(),
                vo.getDeviceName(), boolToChar(vo.isEnabled()), vo.getLoggingType(),
                vo.getIntervalLoggingPeriodType(), vo.getIntervalLoggingPeriod(), vo.getIntervalLoggingType(),
                vo.getTolerance(), boolToChar(vo.isPurgeOverride()), vo.getPurgeType(), vo.getPurgePeriod(),
                vo.getDefaultCacheSize(), boolToChar(vo.isDiscardExtremeValues()), vo.getEngineeringUnits(),
                vo.getRollup(),
                vo.getPointLocator().getDataTypeId(), boolToChar(vo.getPointLocator().isSettable())};
    }

    @Override
    protected LinkedHashMap<String, Integer> getPropertyTypeMap() {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put("id", Types.INTEGER);
        map.put("data", Types.BINARY); //Locator
        map.put("xid", Types.VARCHAR); //Xid
        map.put("dataSourceId",Types.INTEGER); //Dsid
        map.put("name", Types.VARCHAR); //Name
        map.put("deviceName", Types.VARCHAR); //Device Name
        map.put("enabled", Types.CHAR); //Enabled
        map.put("loggingType", Types.INTEGER); //Logging Type
        map.put("intervalLoggingPeriodType", Types.INTEGER); //Interval Logging Period Type
        map.put("intervalLoggingPeriod", Types.INTEGER); //Interval Logging Period
        map.put("intervalLoggingType", Types.INTEGER); //Interval Logging Type
        map.put("tolerance", Types.DOUBLE); //Tolerance
        map.put("purgeOverride", Types.CHAR); //Purge Override
        map.put("purgeType", Types.INTEGER); //Purge Type
        map.put("purgePeriod", Types.INTEGER); //Purge Period
        map.put("defaultCacheSize", Types.INTEGER); //Default Cache Size
        map.put("discardExtremeValues", Types.CHAR); //Discard Extremem Values
        map.put("engineeringUnits", Types.INTEGER); //get Engineering Units
        map.put("rollup", Types.INTEGER); //Common.Rollups type
        map.put("dataTypeId", Types.INTEGER);
        map.put("settable", Types.CHAR);
        return map;
    }

    @Override
    protected List<JoinClause> getJoins() {
        List<JoinClause> joins = new ArrayList<JoinClause>();
        joins.add(new JoinClause(JOIN, SchemaDefinition.DATASOURCES_TABLE, "ds", "ds.id = dp.dataSourceId"));
        return joins;
    }

    @Override
    protected List<Index> getIndexes() {
        List<Index> indexes = new ArrayList<Index>();
        List<QueryAttribute> columns = new ArrayList<QueryAttribute>();
        //Data Source Name Force
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("nameIndex", "ds", columns, "ASC"));

        //Data Source xid Force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("xid", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("dataSourcesUn1", "ds", columns, "ASC"));

        //DeviceNameName Index Force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("deviceName", new HashSet<String>(), Types.VARCHAR));
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("deviceNameNameIndex", "dp", columns, "ASC"));

        //xid point name force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("xid", new HashSet<String>(), Types.VARCHAR));
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("xidNameIndex", "dp", columns, "ASC"));

        return indexes;
    }

    @Override
    protected Map<String, IntStringPair> getPropertiesMap() {
        HashMap<String, IntStringPair> map = new HashMap<String, IntStringPair>();
        map.put("dataSourceName", new IntStringPair(Types.VARCHAR, "ds.name"));
        map.put("dataSourceTypeName", new IntStringPair(Types.VARCHAR, "ds.dataSourceType"));
        map.put("dataSourceXid", new IntStringPair(Types.VARCHAR, "ds.xid"));
        return map;
    }

    @Override
    public RowMapper<DataPointVO> getRowMapper() {
        return new DataPointMapper();
    }

    class DataPointMapper implements RowMapper<DataPointVO> {
        @Override
        public DataPointVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            int i = 0;
            int id = (rs.getInt(++i));

            DataPointVO dp = (DataPointVO) SerializationHelper.readObjectInContext(rs.getBinaryStream(++i));

            dp.setId(id);
            dp.setXid(rs.getString(++i));
            dp.setDataSourceId(rs.getInt(++i));
            dp.setName(rs.getString(++i));
            dp.setDeviceName(rs.getString(++i));
            dp.setEnabled(charToBool(rs.getString(++i)));
            dp.setLoggingType(rs.getInt(++i));
            dp.setIntervalLoggingPeriodType(rs.getInt(++i));
            dp.setIntervalLoggingPeriod(rs.getInt(++i));
            dp.setIntervalLoggingType(rs.getInt(++i));
            dp.setTolerance(rs.getDouble(++i));
            dp.setPurgeOverride(charToBool(rs.getString(++i)));
            dp.setPurgeType(rs.getInt(++i));
            dp.setPurgePeriod(rs.getInt(++i));
            dp.setDefaultCacheSize(rs.getInt(++i));
            dp.setDiscardExtremeValues(charToBool(rs.getString(++i)));
            dp.setEngineeringUnits(rs.getInt(++i));

            // read and discard dataTypeId
            rs.getInt(++i);
            // read and discard settable boolean
            rs.getString(++i);

            // Data source information from Extra Joins set in Constructor
            dp.setDataSourceName(rs.getString(++i));
            dp.setDataSourceXid(rs.getString(++i));
            dp.setDataSourceTypeName(rs.getString(++i));

            dp.ensureUnitsCorrect();
            return dp;
        }
    }

    /**
     * Loads the event detectors, point comments and tags
     * @param vo
     */
    public void loadPartialRelationalData(DataPointVO vo) {
        this.loadEventDetectors(vo);
        this.loadPointComments(vo);
        vo.setTags(DataPointTagsDao.getInstance().getTagsForDataPointId(vo.getId()));
    }

    private void loadPartialRelationalData(List<DataPointVO> dps) {
        for (DataPointVO dp : dps) {
            loadPartialRelationalData(dp);
        }
    }

    /**
     * Loads the event detectors, point comments, tags data source and template name
     * Used by getFull()
     * @param vo
     */
    @Override
    public void loadRelationalData(DataPointVO vo) {
        this.loadPartialRelationalData(vo);
        this.loadDataSource(vo);
        //Populate permissions
        vo.setReadRoles(RoleDao.getInstance().getRoles(vo, PermissionService.READ));
        vo.setSetRoles(RoleDao.getInstance().getRoles(vo, PermissionService.SET));
    }

    @Override
    public void saveRelationalData(DataPointVO vo, boolean insert) {
        saveEventDetectors(vo);

        Map<String, String> tags = vo.getTags();
        if (tags == null) {
            if (!insert) {
                // only delete the name and device tags, leave existing tags intact
                DataPointTagsDao.getInstance().deleteNameAndDeviceTagsForDataPointId(vo.getId());
            }
            tags = Collections.emptyMap();
        } else if (!insert) {
            // we only need to delete tags when doing an update
            DataPointTagsDao.getInstance().deleteTagsForDataPointId(vo.getId());
        }

        DataPointTagsDao.getInstance().insertTagsForDataPoint(vo, tags);
        //Replace the role mappings
        RoleDao.getInstance().replaceRolesOnVoPermission(vo.getReadRoles(), vo, PermissionService.READ, insert);
        RoleDao.getInstance().replaceRolesOnVoPermission(vo.getSetRoles(), vo, PermissionService.SET, insert);
    }

    /**
     * Load the datasource info into the DataPoint
     *
     * @param vo
     * @return
     */
    public void loadDataSource(DataPointVO vo) {

        //Get the values from the datasource table
        //TODO Could speed this up if necessary...
        DataSourceVO<?> dsVo = DataSourceDao.getInstance().get(vo.getDataSourceId(), false);
        vo.setDataSourceName(dsVo.getName());
        vo.setDataSourceTypeName(dsVo.getDefinition().getDataSourceTypeName());
        vo.setDataSourceXid(dsVo.getXid());

    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @return
     */
    public List<DataPointVO> dataPointsForUser(User user) {
        List<DataPointVO> result = new ArrayList<>();
        dataPointsForUser(user, (item, index) -> result.add(item));
        return result;
    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @param callback
     */
    public void dataPointsForUser(User user, MappedRowCallback<DataPointVO> callback) {
        dataPointsForUser(user, callback, null, null, null);
    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @param callback
     * @param sort (may be null)
     * @param limit (may be null)
     * @param offset (may be null)
     */
    public void dataPointsForUser(User user, MappedRowCallback<DataPointVO> callback, List<SortField<Object>> sort, Integer limit, Integer offset) {
        Condition condition = null;
        if (!user.hasAdminRole()) {
            condition = this.userHasPermission(user);
        }
        SelectJoinStep<Record> select = this.create.select(this.fields).from(this.joinedTable);
        this.customizedQuery(select, condition, sort, limit, offset, callback);
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @return
     */
    public List<DataPointVO> dataPointsForTags(Map<String, String> restrictions, User user) {
        List<DataPointVO> result = new ArrayList<>();
        dataPointsForTags(restrictions, user, (item, index) -> result.add(item));
        return result;
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @param callback
     */
    public void dataPointsForTags(Map<String, String> restrictions, User user, MappedRowCallback<DataPointVO> callback) {
        dataPointsForTags(restrictions, user, callback, null, null, null);
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @param callback
     * @param sort (may be null)
     * @param limit (may be null)
     * @param offset (may be null)
     */
    public void dataPointsForTags(Map<String, String> restrictions, User user, MappedRowCallback<DataPointVO> callback, List<SortField<Object>> sort, Integer limit, Integer offset) {
        if (restrictions.isEmpty()) {
            throw new IllegalArgumentException("restrictions should not be empty");
        }

        Map<String, Name> tagKeyToColumn = DataPointTagsDao.getInstance().tagKeyToColumn(restrictions.keySet());

        List<Condition> conditions = restrictions.entrySet().stream().map(e -> {
            return DSL.field(DATA_POINT_TAGS_PIVOT_ALIAS.append(tagKeyToColumn.get(e.getKey()))).eq(e.getValue());
        }).collect(Collectors.toCollection(ArrayList::new));

        if (!user.hasAdminRole()) {
            conditions.add(this.userHasPermission(user));
        }

        Table<Record> pivotTable = DataPointTagsDao.getInstance().createTagPivotSql(tagKeyToColumn).asTable().as(DATA_POINT_TAGS_PIVOT_ALIAS);

        SelectOnConditionStep<Record> select = this.create.select(this.fields).from(this.joinedTable).leftJoin(pivotTable)
                .on(DataPointTagsDao.PIVOT_ALIAS_DATA_POINT_ID.eq(ID));

        this.customizedQuery(select, DSL.and(conditions), sort, limit, offset, callback);
    }

    @Override
    protected RQLToCondition createRqlToCondition() {
        // we create one every time as they are stateful for this DAO
        return null;
    }

    @Override
    public <R extends Record> SelectJoinStep<R> joinTables(SelectJoinStep<R> select, ConditionSortLimit conditions) {
        if (conditions instanceof ConditionSortLimitWithTagKeys) {
            Map<String, Name> tagKeyToColumn = ((ConditionSortLimitWithTagKeys) conditions).getTagKeyToColumn();
            if (!tagKeyToColumn.isEmpty()) {
                Table<Record> pivotTable = DataPointTagsDao.getInstance().createTagPivotSql(tagKeyToColumn).asTable().as(DATA_POINT_TAGS_PIVOT_ALIAS);

                return select.leftJoin(pivotTable)
                        .on(DataPointTagsDao.PIVOT_ALIAS_DATA_POINT_ID.eq(ID));
            }
        }
        return select;
    }

    @Override
    public ConditionSortLimitWithTagKeys rqlToCondition(ASTNode rql) {
        // RQLToConditionWithTagKeys is stateful, we need to create a new one every time
        RQLToConditionWithTagKeys rqlToSelect = new RQLToConditionWithTagKeys(this.propertyToField, this.valueConverterMap);
        return rqlToSelect.visit(rql);
    }

    public static final String PERMISSION_START_REGEX = "(^|[,])\\s*";
    public static final String PERMISSION_END_REGEX = "\\s*($|[,])";

    public Condition userHasPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size() * 3);

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(READ_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(SET_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    public Condition userHasSetPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size() * 2);

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(SET_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    public Condition userHasEditPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size());

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    Condition fieldMatchesUserPermission(Field<String> field, String userPermission) {
        return DSL.or(
                field.eq(userPermission),
                DSL.and(
                        field.isNotNull(),
                        field.notEqual(""),
                        field.likeRegex(PERMISSION_START_REGEX + userPermission + PERMISSION_END_REGEX)
                        )
                );
    }

    protected void notifyTagsUpdated(DataPointVO dataPoint) {
        this.eventPublisher.publishEvent(new DataPointTagsUpdatedEvent(this, dataPoint));
    }

    @Override
    protected Map<String, Function<Object, Object>> createValueConverterMap() {
        Map<String, Function<Object, Object>> map = new HashMap<>(super.createValueConverterMap());
        map.put("dataTypeId", value -> {
            if (value instanceof String) {
                return DataTypes.CODES.getId((String) value);
            }
            return value;
        });
        return map;
    }

    @Override
    protected Map<String, Field<Object>> createPropertyToField() {
        Map<String, Field<Object>> map = super.createPropertyToField();
        map.put("dataType", map.get("dataTypeId"));
        return map;
    }
    
    @Override
    protected ResultSetExtractor<List<DataPointVO>> getListResultSetExtractor() {
        return getListResultSetExtractor((e, rs) -> {
            if (e.getCause() instanceof ModuleNotLoadedException) {
                try {
                    LOG.error("Data point with xid '" + rs.getString("xid")
                    + "' could not be loaded. Is its module missing?", e.getCause());
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
            MappedRowCallback<DataPointVO> callback) {
        return getCallbackResultSetExtractor(callback, (e, rs) -> {
            if (e.getCause() instanceof ModuleNotLoadedException) {
                try {
                    LOG.error("Data point with xid '" + rs.getString("xid")
                    + "' could not be loaded. Is its module missing?", e.getCause());
                }catch(SQLException e1) {
                    LOG.error(e.getMessage(), e);
                }
            }else {
                LOG.error(e.getMessage(), e);
            }
        }); 
    }

}
