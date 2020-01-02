/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import com.infiniteautomation.mango.util.exception.NotFoundException;
import com.infiniteautomation.mango.util.exception.ValidationException;
import com.serotonin.m2m2.db.dao.AbstractBasicDao;
import com.serotonin.m2m2.vo.AbstractBasicVO;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.role.Role;

/**
 * @author Terry Packer
 *
 */
public abstract class AbstractBasicVOServiceWithPermissionsTestBase<VO extends AbstractBasicVO, DAO extends AbstractBasicDao<VO>, SERVICE extends AbstractBasicVOService<VO,DAO>> extends AbstractBasicVOServiceTest<VO, DAO, SERVICE> {

    public AbstractBasicVOServiceWithPermissionsTestBase() {
        
    }
    
    public AbstractBasicVOServiceWithPermissionsTestBase(boolean enableWebDb, int webDbPort) {
        super(enableWebDb, webDbPort);
    }
    
    /**
     * The type name for the create permission of the VO
     * @return
     */
    abstract String getCreatePermissionType();
    abstract void setReadRoles(Set<Role> roles, VO vo);
    abstract void setEditRoles(Set<Role> roles, VO vo);
    
    @Test(expected = PermissionException.class)
    public void testCreatePrivilegeFails() {
        VO vo = newVO();
        service.insert(vo, editUser);
    }

    @Test
    public void testCreatePrivilegeSuccess() {
        runTest(() -> {
            VO vo = newVO();
            addRoleToCreatePermission(editRole);
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
            service.insert(vo, editUser);
        });
    }
    
    @Test
    public void testUserReadRole() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), readUser);
            assertVoEqual(vo, fromDb);
        });
    }
    
    @Test(expected = PermissionException.class)
    public void testUserReadRoleFails() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.emptySet(), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), readUser);
            assertVoEqual(vo, fromDb);
        });
    }
    
    @Test(expected = ValidationException.class)
    public void testReadRolesCannotBeNull() {
        VO vo = newVO();
        setReadRoles(null, vo);
        service.insert(vo, systemSuperadmin);
    }
    
    @Test(expected = ValidationException.class)
    public void testCannotRemoveReadAccess() {
        VO vo = newVO();
        setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
        setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
        service.insert(vo, systemSuperadmin);
        VO fromDb = service.get(vo.getId(), readUser);
        assertVoEqual(vo, fromDb);
        setReadRoles(Collections.emptySet(), fromDb);
        service.update(fromDb.getId(), fromDb, readUser);
    }
    
    @Test(expected = ValidationException.class)
    public void testAddReadRoleUserDoesNotHave() {
        VO vo = newVO();
        setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
        setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
        service.insert(vo, systemSuperadmin);
        VO fromDb = service.get(vo.getId(), readUser);
        assertVoEqual(vo, fromDb);
        setReadRoles(Collections.singleton(roleService.getSuperadminRole()), fromDb);
        service.update(fromDb.getId(), fromDb, readUser);
    }
    
    @Test
    public void testUserEditRole() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), readUser);
            assertVoEqual(vo, fromDb);
            service.update(fromDb.getId(), fromDb, readUser);
            VO updated = service.get(fromDb.getId(), readUser);
            assertVoEqual(fromDb, updated);
        });
    }
    
    @Test(expected = PermissionException.class)
    public void testUserEditRoleFails() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            setEditRoles(Collections.emptySet(), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), readUser);
            assertVoEqual(vo, fromDb);
            service.update(fromDb.getId(), fromDb, readUser);
            VO updated = service.get(fromDb.getId(), readUser);
            assertVoEqual(fromDb, updated);
        });
    }
    
    @Test(expected = ValidationException.class)
    public void testEditRolesCannotBeNull() {
        VO vo = newVO();
        setEditRoles(null, vo);
        service.insert(vo, systemSuperadmin);
    }
    
    @Test(expected = ValidationException.class)
    public void testCannotRemoveEditAccess() {
        VO vo = newVO();
        setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
        setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
        service.insert(vo, systemSuperadmin);
        VO fromDb = service.get(vo.getId(), readUser);
        assertVoEqual(vo, fromDb);
        setEditRoles(Collections.emptySet(), fromDb);
        service.update(fromDb.getId(), fromDb, readUser);
    }
    
    @Test(expected = ValidationException.class)
    public void testAddEditRoleUserDoesNotHave() {
        VO vo = newVO();
        setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
        setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
        service.insert(vo, systemSuperadmin);
        VO fromDb = service.get(vo.getId(), readUser);
        assertVoEqual(vo, fromDb);
        setEditRoles(Collections.singleton(roleService.getSuperadminRole()), fromDb);
        service.update(fromDb.getId(), fromDb, readUser);
    }
    
    @Test
    public void testUserCanDelete() {
        runTest(() -> {
            VO vo = newVO();
            addRoleToCreatePermission(editRole);
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            setEditRoles(Collections.singleton(roleService.getUserRole()), vo);
            vo = service.insert(vo, editUser);
            service.delete(vo.getId(), editUser);
        });
    }
    
    @Test(expected = PermissionException.class)
    public void testUserDeleteFails() {
        runTest(() -> {
            VO vo = newVO();
            service.insert(vo, systemSuperadmin);
            service.delete(vo.getId(), editUser);
        });
    }
    
    @Test(expected = PermissionException.class)
    public void testSuperadminReadRole() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(roleService.getSuperadminRole()), vo);
            service.insert(vo, systemSuperadmin);
            service.get(vo.getId(), readUser);
        });
    }
    
    @Test(expected = PermissionException.class)
    public void testSuperadminEditRole() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(roleService.getUserRole()), vo);
            setEditRoles(Collections.singleton(roleService.getSuperadminRole()), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), readUser);
            assertVoEqual(vo, fromDb);
            service.update(fromDb.getId(), fromDb, readUser);            
        });
    }
    
    @Test
    public void testDeleteRoleUpdateVO() {
        runTest(() -> {
            VO vo = newVO();
            setReadRoles(Collections.singleton(readRole), vo);
            setEditRoles(Collections.singleton(editRole), vo);
            service.insert(vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), systemSuperadmin);
            assertVoEqual(vo, fromDb);
            roleService.delete(editRole.getId(), systemSuperadmin);
            roleService.delete(readRole.getId(), systemSuperadmin);
            VO updated = service.get(fromDb.getId(), systemSuperadmin);
            setReadRoles(Collections.emptySet(), fromDb);
            setEditRoles(Collections.emptySet(), fromDb);
            assertVoEqual(fromDb, updated);
        });
    }

    @Test(expected = NotFoundException.class)
    @Override
    public void testDelete() {
        runTest(() -> {
            VO vo = insertNewVO();
            setReadRoles(Collections.singleton(readRole), vo);
            setEditRoles(Collections.singleton(editRole), vo);
            service.update(vo.getId(), vo, systemSuperadmin);
            VO fromDb = service.get(vo.getId(), systemSuperadmin);
            assertVoEqual(vo, fromDb);
            service.delete(vo.getId(), systemSuperadmin);
            
            //Ensure the mappings are gone
            assertEquals(0, roleService.getDao().getRoles(vo, PermissionService.READ).size());
            assertEquals(0, roleService.getDao().getRoles(vo, PermissionService.EDIT).size());
            
            service.get(vo.getId(), systemSuperadmin);
        });
    }
    
    void addRoleToCreatePermission(Role vo) {
        String permissionType = getCreatePermissionType();
        if(permissionType != null) {
            roleService.addRoleToPermission(vo, getCreatePermissionType(), systemSuperadmin);
        }
    }
}
