/*
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.webapp;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.infiniteautomation.mango.spring.MangoCommonConfiguration;
import com.serotonin.m2m2.web.mvc.spring.MangoJspDispatcherConfiguration;
import com.serotonin.m2m2.web.mvc.spring.MangoRootWebContextConfiguration;

/**
 *
 * <p>Class to hook into the Web Application Initialization process, creates the Spring Application contexts.</p>
 *
 * <p>We use AnnotationConfigWebApplicationContexts to perform configuration that previously was only able to be done via XML.</p>
 *
 * Context hierarchy looks like this:
 * <pre>
 * runtimeContext -> rootWebContext -> jspDispatcherContext
 *                                  -> rootRestDispatcherContext -> restv1DispatcherContext
 *                                                               -> restv2DispatcherContext
 * </pre>
 *
 * @author Terry Packer
 * @author Jared Wiltshire
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class MangoWebApplicationInitializer implements ServletContainerInitializer {

    private final ApplicationContext parent;
    private final Environment env;

    @Autowired
    private MangoWebApplicationInitializer(ApplicationContext parent, Environment env) {
        this.parent = parent;
        this.env = env;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException {
        /**
         * Root web application context configuration
         */

        // Create the Spring 'root' web application context
        AnnotationConfigWebApplicationContext rootWebContext = new AnnotationConfigWebApplicationContext();
        rootWebContext.setId(MangoRootWebContextConfiguration.CONTEXT_ID);
        rootWebContext.setParent(this.parent);
        rootWebContext.register(MangoRootWebContextConfiguration.class);
        rootWebContext.setServletContext(context);
        rootWebContext.refresh();

        // find any WebApplicationInitializer in the web context configuration and call them during startup (before initialization) so
        // they have an opportunity to add filters / servlets
        for (WebApplicationInitializer initializer : MangoCommonConfiguration.beansOfType(rootWebContext, WebApplicationInitializer.class)) {
            initializer.onStartup(context);
        }

        // Manage the lifecycle of the root application context
        context.addListener(new ContextLoaderListener(rootWebContext));

        /**
         * JSP dispatcher application context configuration
         */

        if (this.env.getProperty("web.jsp.enabled", Boolean.class, true)) {
            // Create the dispatcher servlet's Spring application context
            AnnotationConfigWebApplicationContext jspDispatcherContext = new AnnotationConfigWebApplicationContext();
            jspDispatcherContext.setId(MangoJspDispatcherConfiguration.CONTEXT_ID);
            jspDispatcherContext.setParent(rootWebContext);
            jspDispatcherContext.register(MangoJspDispatcherConfiguration.class);

            // Register and map the JSP dispatcher servlet
            ServletRegistration.Dynamic jspDispatcher = context.addServlet(MangoJspDispatcherConfiguration.DISPATCHER_NAME, new DispatcherServlet(jspDispatcherContext));
            jspDispatcher.setLoadOnStartup(1);
            jspDispatcher.addMapping("*.htm", "*.shtm");
        }
    }
}