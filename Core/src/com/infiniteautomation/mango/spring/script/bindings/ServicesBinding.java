/*
 * Copyright (C) 2020 Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.script.bindings;

import java.util.Map;

import javax.script.Bindings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.infiniteautomation.mango.permission.MangoPermission;
import com.infiniteautomation.mango.spring.script.MangoScript;
import com.infiniteautomation.mango.spring.script.permissions.ServicesBindingPermission;
import com.serotonin.m2m2.module.ScriptBindingsDefinition;

/**
 * @author Jared Wiltshire
 */
public class ServicesBinding extends ScriptBindingsDefinition {

    @Autowired
    ServicesBindingPermission servicesBindingPermission;

    @Autowired
    ApplicationContext context;

    @Override
    public void addBindings(MangoScript script, Bindings engineBindings, ScriptNativeConverter converter) {
        Map<String, Object> services = context.getBeansWithAnnotation(Service.class);
        engineBindings.put("services", converter.convert(services));
    }

    @Override
    public MangoPermission requiredPermission() {
        return servicesBindingPermission.getPermission();
    }

}
