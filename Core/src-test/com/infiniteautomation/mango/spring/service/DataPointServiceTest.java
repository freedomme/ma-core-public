/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.infiniteautomation.mango.permission.MangoPermission;
import com.infiniteautomation.mango.spring.db.DataPointTableDefinition;
import com.infiniteautomation.mango.util.exception.NotFoundException;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.dao.DataPointDao;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.dataPoint.MockPointLocatorVO;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.dataSource.mock.MockDataSourceVO;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.m2m2.vo.role.Role;

/**
 * @author Terry Packer
 *
 */
public class DataPointServiceTest<T extends DataSourceVO> extends AbstractVOServiceWithPermissionsTest<DataPointVO, DataPointTableDefinition, DataPointDao, DataPointService> {

    private DataSourceService dataSourceService;

    public DataPointServiceTest() {
    }

    @Override
    public void before() {
        super.before();
        dataSourceService = Common.getBean(DataSourceService.class);
    }

    @Test
    @Override
    public void testCreatePrivilegeSuccess() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            getService().permissionService.runAsSystemAdmin(() -> {
                DataSourceVO ds = dataSourceService.get(vo.getDataSourceId());
                ds.setEditPermission(MangoPermission.createOrSet(editRole));
                dataSourceService.update(ds.getXid(), ds);
                setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
                vo.setSetPermission(MangoPermission.createOrSet(roleService.getUserRole()));
            });
            getService().permissionService.runAs(editUser, () -> {
                service.insert(vo);
            });
        });
    }

    @Test
    @Override
    public void testUserCanDelete() {
        runTest(() -> {
            getService().permissionService.runAs(editUser, () -> {
                DataPointVO vo = newVO(readUser);
                addRoleToCreatePermission(editRole);
                setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
                setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
                vo.setSetPermission(MangoPermission.createOrSet(roleService.getUserRole()));
                vo = service.insert(vo);
                service.delete(vo.getId());
            });
        });
    }

    @Test
    @Override
    public void testUserEditRole() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            vo.setSetPermission(MangoPermission.createOrSet(PermissionHolder.USER_ROLE));
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                fromDb.setName("read user edited me");
                service.update(fromDb.getXid(), fromDb);
                DataPointVO updated = service.get(fromDb.getId());
                assertVoEqual(fromDb, updated);
            });
        });
    }

    @Test(expected = PermissionException.class)
    @Override
    public void testUserEditRoleFails() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            setReadPermission(MangoPermission.createOrSet(PermissionHolder.USER_ROLE), vo);
            setEditPermission(MangoPermission.createOrSet(Collections.emptySet()), vo);
            vo.setSetPermission(MangoPermission.createOrSet(PermissionHolder.USER_ROLE));
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                fromDb.setName("read user edited me");
                service.update(fromDb.getXid(), fromDb);
                DataPointVO updated = service.get(fromDb.getId());
                assertVoEqual(fromDb, updated);
            });
        });
    }

    @Test()
    @Override
    public void testCannotRemoveEditAccess() {
        //No-op will be tested in the data source service test
    }

    @Test()
    @Override
    public void testAddEditRoleUserDoesNotHave() {
        //No-op will be tested in the data source service test
    }

    @Test(expected = NotFoundException.class)
    @Override
    public void testDelete() {
        runTest(() -> {
            getService().permissionService.runAsSystemAdmin(() -> {
                DataPointVO vo = insertNewVO(editUser);
                setReadPermission(MangoPermission.createOrSet(readRole), vo);
                setEditPermission(MangoPermission.createOrSet(editRole), vo);
                vo.setSetPermission(MangoPermission.createOrSet(readRole));
                service.update(vo.getXid(), vo);
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                service.delete(vo.getId());

                service.get(vo.getId());
            });
        });
    }

    @Test
    public void testCannotRemoveSetAccess() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            vo.setSetPermission(MangoPermission.createOrSet(roleService.getUserRole()));
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                fromDb.setName("read user edited me");
                fromDb.setSetPermission(new MangoPermission());
                service.update(fromDb.getXid(), fromDb);
            });
        }, "setPermission");
    }

    @Test
    public void testSetRolesCannotBeNull() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            vo.setSetPermission(null);
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                fromDb.setName("read user edited me");
                fromDb.setSetPermission(new MangoPermission());
                service.update(fromDb.getXid(), fromDb);
            });
        }, "setPermission");
    }

    @Override
    @Test
    public void testEditRolesCannotBeNull() {
        //Not a thing
    }

    @Test
    public void testAddSetRoleUserDoesNotHave() {
        runTest(() -> {
            DataPointVO vo = newVO(editUser);
            setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            vo.setSetPermission(MangoPermission.createOrSet(roleService.getUserRole()));
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                fromDb.setName("read user edited me");
                fromDb.setSetPermission(MangoPermission.createOrSet(roleService.getSuperadminRole()));
                service.update(fromDb.getXid(), fromDb);
            });
        }, "setPermission", "setPermission");
    }

    @Test
    @Override
    public void testAddReadRoleUserDoesNotHave() {
        runTest(() -> {
            DataPointVO vo = newVO(readUser);
            setReadPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            vo.setSetPermission(MangoPermission.createOrSet(roleService.getUserRole()));
            setEditPermission(MangoPermission.createOrSet(roleService.getUserRole()), vo);
            getService().permissionService.runAsSystemAdmin(() -> {
                service.insert(vo);
            });
            getService().permissionService.runAs(readUser, () -> {
                DataPointVO fromDb = service.get(vo.getId());
                assertVoEqual(vo, fromDb);
                setReadPermission(MangoPermission.createOrSet(roleService.getSuperadminRole()), fromDb);
                service.update(fromDb.getId(), fromDb);
            });
        }, getReadRolesContextKey(), getReadRolesContextKey());
    }

    @Override
    String getCreatePermissionType() {
        return null;
    }

    @Override
    void setReadPermission(MangoPermission permission, DataPointVO vo) {
        vo.setReadPermission(permission);
    }

    @Override
    void setEditPermission(MangoPermission permission, DataPointVO vo) {
        getService().permissionService.runAsSystemAdmin(() -> {
            DataSourceVO ds = dataSourceService.get(vo.getDataSourceId());
            ds.setEditPermission(permission);
            dataSourceService.update(ds.getXid(), ds);
        });
        vo.setSetPermission(permission);
    }

    @Override
    DataPointService getService() {
        return Common.getBean(DataPointService.class);
    }

    @Override
    DataPointDao getDao() {
        return DataPointDao.getInstance();
    }

    @Override
    void assertVoEqual(DataPointVO expected, DataPointVO actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getXid(), actual.getXid());
        assertEquals(expected.getName(), actual.getName());

        assertEquals(expected.getDataSourceId(), actual.getDataSourceId());
        assertEquals(expected.getPointLocator().getDataTypeId(), actual.getPointLocator().getDataTypeId());

        assertPermission(expected.getReadPermission(), actual.getReadPermission());
        assertPermission(expected.getSetPermission(), actual.getSetPermission());
    }

    @Override
    DataPointVO newVO(User user) {
        return getService().permissionService.runAsSystemAdmin(() -> {;
        DataSourceVO mock = dataSourceService.insert(createDataSource());
        //Create the point
        DataPointVO vo = new DataPointVO();
        vo.setXid(UUID.randomUUID().toString());
        vo.setName(UUID.randomUUID().toString());
        vo.setDataSourceId(mock.getId());
        vo.setPointLocator(new MockPointLocatorVO());
        //TODO Flesh out all fields

        return vo;
        });
    }

    @Override
    DataPointVO updateVO(DataPointVO existing) {
        DataPointVO copy = existing.copy();

        return copy;
    }

    @SuppressWarnings("unchecked")
    T createDataSource() {
        MockDataSourceVO dsVo = new MockDataSourceVO();
        dsVo.setName("permissions_test_datasource");
        return (T) dsVo;
    }

    @Override
    void addReadRoleToFail(Role role, DataPointVO vo) {
        vo.getReadPermission().getRoles().add(Collections.singleton(role));
    }

    @Override
    String getReadRolesContextKey() {
        return "readPermission";
    }

    @Override
    void addEditRoleToFail(Role role, DataPointVO vo) {
        vo.getSetPermission().getRoles().add(Collections.singleton(role));
    }

    @Override
    String getEditRolesContextKey() {
        return "setPermission";
    }
}
