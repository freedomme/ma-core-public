/**
 * Copyright (C) 2020  Infinite Automation Software. All rights reserved.
 */

package com.serotonin.m2m2.db.dao;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.infiniteautomation.mango.spring.service.DataSourceService;
import com.infiniteautomation.mango.spring.service.EventHandlerService;
import com.infiniteautomation.mango.spring.service.PermissionService;
import com.infiniteautomation.mango.spring.service.RoleService;
import com.serotonin.db.spring.ExtendedJdbcTemplate;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.MangoTestBase;
import com.serotonin.m2m2.MockMangoLifecycle;
import com.serotonin.m2m2.db.H2InMemoryDatabaseProxy;
import com.serotonin.m2m2.module.definitions.event.handlers.ProcessEventHandlerDefinition;
import com.serotonin.m2m2.rt.event.type.DataSourceEventType;
import com.serotonin.m2m2.rt.event.type.EventType;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.dataSource.mock.MockDataSourceVO;
import com.serotonin.m2m2.vo.event.AbstractEventHandlerVO;
import com.serotonin.m2m2.vo.event.ProcessEventHandlerVO;
import com.serotonin.m2m2.vo.role.Role;
import com.serotonin.m2m2.vo.role.RoleVO;

/**
 *
 * @author Terry Packer
 */
public class DataPointDaoDeadlockDetection extends MangoTestBase {

    static final Log LOG = LogFactory.getLog(DataPointDaoDeadlockDetection.class);

    @Test
    public void detectDeadlockUsingDaos() {

        //This will create 2x threads for each operating as one of the desired problem scenarios
        int numThreads = 5; //25;
        int numDataSources = 10; //100;
        AtomicInteger running = new AtomicInteger(numThreads * 2);

        PermissionService permissionService = Common.getBean(PermissionService.class);

        //Insert 0 roles
        Set<Role> roles = new HashSet<>();

        DataSource dataSource = Common.databaseProxy.getDataSource();
        JdbcConnectionPool pool = (JdbcConnectionPool)dataSource;
        pool.setMaxConnections(numThreads*100);

        AtomicInteger failures = new AtomicInteger();

        for(int i=0; i<numThreads; i++) {
            //#5 lock eventHandlerMappings and roleMappings and then try to lock dataSources
            // Basically delete a data source
            new Thread() {
                @Override
                public void run() {
                    try {
                        permissionService.runAsSystemAdmin(() -> {
                            for(int i=0; i<numDataSources; i++) {

                                //Insert an event handler
                                EventHandlerService eventHandlerService = Common.getBean(EventHandlerService.class);
                                ProcessEventHandlerVO eh = new ProcessEventHandlerVO();
                                eh.setDefinition(new ProcessEventHandlerDefinition());
                                eh.setName(Common.generateXid("Handler "));
                                eh.setActiveProcessCommand("ls");

                                permissionService.runAsSystemAdmin(() -> {eventHandlerService.insert(eh);});

                                ExtendedJdbcTemplate ejt = new ExtendedJdbcTemplate();
                                ejt.setDataSource(dataSource);

                                //Get event handler
                                AbstractEventHandlerVO myEventHandler = eventHandlerService.get(eh.getXid());

                                //Create data source
                                MockDataSourceVO ds = new MockDataSourceVO();
                                ds.setName(Common.generateXid("Mock "));
                                ds.setEditRoles(roles);

                                DataSourceService dataSourceService = Common.getBean(DataSourceService.class);
                                dataSourceService.insert(ds);

                                //Insert a mapping
                                myEventHandler.setEventTypes(Arrays.asList(new DataSourceEventType(ds.getId(), ds.getPollAbortedExceptionEventId())));
                                eventHandlerService.update(eh.getXid(), myEventHandler);

                                dataSourceService.delete(ds);
                            }
                        });
                    }catch(Exception e){
                        e.printStackTrace();
                        failures.getAndIncrement();
                    }finally {
                        running.decrementAndGet();
                    }
                };
            }.start();

            //#8 lock dataSources and try to lock roleMappings
            //Basically update a data source
            new Thread() {
                @Override
                public void run() {
                    try {
                        permissionService.runAsSystemAdmin(() -> {
                            for(int i=0; i<numDataSources; i++) {
                                ExtendedJdbcTemplate ejt = new ExtendedJdbcTemplate();
                                ejt.setDataSource(dataSource);

                                //Insert an event handler
                                EventHandlerService eventHandlerService = Common.getBean(EventHandlerService.class);
                                ProcessEventHandlerVO eh = new ProcessEventHandlerVO();
                                eh.setDefinition(new ProcessEventHandlerDefinition());
                                eh.setName(Common.generateXid("Handler "));
                                eh.setActiveProcessCommand("ls");

                                permissionService.runAsSystemAdmin(() -> {eventHandlerService.insert(eh);});

                                //Get event handler
                                AbstractEventHandlerVO myEventHandler = eventHandlerService.get(eh.getXid());

                                //Create data source
                                MockDataSourceVO ds = new MockDataSourceVO();
                                ds.setName(Common.generateXid("Mock "));
                                ds.setEditRoles(roles);

                                DataSourceService dataSourceService = Common.getBean(DataSourceService.class);
                                dataSourceService.insert(ds);

                                //Insert a mapping
                                myEventHandler.setEventTypes(Arrays.asList(new DataSourceEventType(ds.getId(), ds.getPollAbortedExceptionEventId())));
                                eventHandlerService.update(eh.getXid(), myEventHandler);

                                ds.setXid(ds.getXid() + 1);
                                dataSourceService.update(ds.getId(), ds);
                            }
                        });
                    }catch(Exception e){
                        e.printStackTrace();
                        failures.getAndIncrement();
                    }finally {
                        running.decrementAndGet();
                    }
                };
            }.start();
        }

        while(running.get() > 0) {
            try { Thread.sleep(100); }catch(Exception e) { }
            if(failures.get() > 0) {
                fail("Failed to perform all queries successfully.");
            }
        }
        if(failures.get() > 0) {
            fail("Failed to perform all queries successfully.");
        }
    }

    @Test
    public void detectDeadlockExplicit() {

        //This will create 2x threads for each operating as one of the desired problem scenarios
        int numThreads = 5;
        int numDataSources = 10;
        AtomicInteger running = new AtomicInteger(numThreads * 2);

        PermissionService permissionService = Common.getBean(PermissionService.class);

        //Insert some roles
        int roleCount = 0;
        RoleService roleService = Common.getBean(RoleService.class);
        List<RoleVO> roleVOs = new ArrayList<>();
        Set<Role> roles = new HashSet<>();
        for(int i=0; i<roleCount; i++) {
            RoleVO role = new RoleVO(Common.NEW_ID, Common.generateXid("ROLE_"), "Role " + i);
            roleVOs.add(role);
            permissionService.runAsSystemAdmin(() -> {roleService.insert(role);});
            roles.add(role.getRole());
        }

        DataSource dataSource = Common.databaseProxy.getDataSource();
        JdbcConnectionPool pool = (JdbcConnectionPool)dataSource;
        pool.setMaxConnections(numThreads*2);

        PlatformTransactionManager transactionManager = Common.databaseProxy.getTransactionManager();

        AtomicInteger failures = new AtomicInteger();

        for(int i=0; i<numThreads; i++) {
            //#5 lock eventHandlerMappings and roleMappings and then try to lock dataSources
            // Basically delete a data source
            new Thread() {
                @Override
                public void run() {
                    try {
                        permissionService.runAsSystemAdmin(() -> {
                            for(int i=0; i<numDataSources; i++) {

                                //Insert an event handler
                                EventHandlerService eventHandlerService = Common.getBean(EventHandlerService.class);
                                ProcessEventHandlerVO eh = new ProcessEventHandlerVO();
                                eh.setDefinition(new ProcessEventHandlerDefinition());
                                eh.setName(Common.generateXid("Handler "));
                                eh.setActiveProcessCommand("ls");

                                permissionService.runAsSystemAdmin(() -> {eventHandlerService.insert(eh);});

                                ExtendedJdbcTemplate ejt = new ExtendedJdbcTemplate();
                                ejt.setDataSource(dataSource);

                                //Get event handler
                                AbstractEventHandlerVO myEventHandler = eventHandlerService.get(eh.getXid());

                                //Create data source
                                MockDataSourceVO ds = new MockDataSourceVO();
                                ds.setName(Common.generateXid("Mock "));
                                ds.setEditRoles(roles);

                                DataSourceService dataSourceService = Common.getBean(DataSourceService.class);
                                dataSourceService.insert(ds);

                                //Insert a mapping
                                myEventHandler.setEventTypes(Arrays.asList(new DataSourceEventType(ds.getId(), ds.getPollAbortedExceptionEventId())));
                                eventHandlerService.update(eh.getXid(), myEventHandler);

                                new TransactionTemplate(transactionManager).execute((status) -> {
                                    //The order of these statements matters for deadlock, we must always lock groups of tables in the same order
                                    ejt.update("DELETE FROM dataSources WHERE id=?", new Object[] {ds.getId()});
                                    ejt.update("DELETE FROM eventHandlersMapping WHERE eventTypeName=? AND eventTypeRef1=?", new Object[] {EventType.EventTypeNames.DATA_SOURCE, ds.getId()});

                                    RoleDao.getInstance().deleteRolesForVoPermission(ds.getId(), DataSourceVO.class.getSimpleName(), PermissionService.EDIT);


                                    return null;
                                });
                            }
                        });
                    }catch(Exception e){
                        e.printStackTrace();
                        failures.getAndIncrement();
                    }finally {
                        running.decrementAndGet();
                    }
                };
            }.start();

            //#8 lock dataSources and try to lock roleMappings
            //Basically update a data source
            new Thread() {
                @Override
                public void run() {
                    try {
                        permissionService.runAsSystemAdmin(() -> {
                            for(int i=0; i<numDataSources; i++) {
                                ExtendedJdbcTemplate ejt = new ExtendedJdbcTemplate();
                                ejt.setDataSource(dataSource);

                                //Insert an event handler
                                EventHandlerService eventHandlerService = Common.getBean(EventHandlerService.class);
                                ProcessEventHandlerVO eh = new ProcessEventHandlerVO();
                                eh.setDefinition(new ProcessEventHandlerDefinition());
                                eh.setName(Common.generateXid("Handler "));
                                eh.setActiveProcessCommand("ls");

                                permissionService.runAsSystemAdmin(() -> {eventHandlerService.insert(eh);});

                                //Get event handler
                                AbstractEventHandlerVO myEventHandler = eventHandlerService.get(eh.getXid());

                                //Create data source
                                MockDataSourceVO ds = new MockDataSourceVO();
                                ds.setName(Common.generateXid("Mock "));
                                ds.setEditRoles(roles);

                                DataSourceService dataSourceService = Common.getBean(DataSourceService.class);
                                dataSourceService.insert(ds);

                                //Insert a mapping
                                myEventHandler.setEventTypes(Arrays.asList(new DataSourceEventType(ds.getId(), ds.getPollAbortedExceptionEventId())));
                                eventHandlerService.update(eh.getXid(), myEventHandler);

                                new TransactionTemplate(transactionManager).execute((status) -> {
                                    ejt.update("UPDATE dataSources SET xid=? WHERE id=?", new Object[] {ds.getXid() + "1", ds.getId()});

                                    RoleDao.getInstance().replaceRolesOnVoPermission(ds.getEditRoles(), ds.getId(), DataSourceVO.class.getSimpleName(), PermissionService.EDIT, false);

                                    return null;
                                });
                            }
                        });
                    }catch(Exception e){
                        e.printStackTrace();
                        failures.getAndIncrement();
                    }finally {
                        running.decrementAndGet();
                    }
                };
            }.start();
        }

        while(running.get() > 0) {
            try { Thread.sleep(100); }catch(Exception e) { }
            if(failures.get() > 0) {
                fail("Failed to perform all queries successfully.");
            }
        }
        if(failures.get() > 0) {
            fail("Failed to perform all queries successfully.");
        }
    }

    @Override
    protected MockMangoLifecycle getLifecycle() {
        MockMangoLifecycle lifecycle = super.getLifecycle();

        lifecycle.setDb(new H2InMemoryDatabaseProxyNoLocking(this.enableH2Web, this.h2WebPort));

        return lifecycle;
    }

    private class H2InMemoryDatabaseProxyNoLocking extends H2InMemoryDatabaseProxy {
        public H2InMemoryDatabaseProxyNoLocking(boolean initWebConsole, Integer webPort) {
            super(initWebConsole, webPort, false);
        }
        @Override
        public String getUrl() {
            return "jdbc:h2:mem:" + databaseName + ";MV_STORE=FALSE;DEFAULT_LOCK_TIMEOUT=10000;";
        }
    }

}