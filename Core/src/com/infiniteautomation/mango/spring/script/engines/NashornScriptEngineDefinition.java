/*
 * Copyright (C) 2020 Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.script.engines;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.function.Function;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;

import org.springframework.beans.factory.annotation.Autowired;

import com.infiniteautomation.mango.permission.MangoPermission;
import com.infiniteautomation.mango.spring.script.MangoScript;
import com.infiniteautomation.mango.spring.script.permissions.LoadFileStorePermission;
import com.infiniteautomation.mango.spring.script.permissions.LoadOtherPermission;
import com.infiniteautomation.mango.spring.script.permissions.LoadWebPermission;
import com.infiniteautomation.mango.spring.script.permissions.NashornPermission;
import com.infiniteautomation.mango.spring.service.FileStoreService;
import com.serotonin.m2m2.module.ScriptEngineDefinition;

import jdk.nashorn.api.scripting.JSObject;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;

/**
 * @author Jared Wiltshire
 */
@SuppressWarnings({"removal", "deprecation", "restriction"})
public class NashornScriptEngineDefinition extends ScriptEngineDefinition {

    @Autowired
    NashornPermission permission;
    @Autowired
    LoadFileStorePermission loadFileStorePermission;
    @Autowired
    LoadWebPermission loadWebPermission;
    @Autowired
    LoadOtherPermission loadOtherPermission;
    @Autowired
    FileStoreService fileStoreService;

    private static final String[] KEYS_TO_REMOVE = new String[] {
            "exit", "quit",
            "load", "loadWithNewGlobal",
            "java", "javax", "javafx", "com", "edu", "org",
            "Packages", "JavaImporter", "Java"
    };

    @Override
    public boolean supports(ScriptEngineFactory engineFactory) {
        return "jdk.nashorn.api.scripting.NashornScriptEngineFactory".equals(engineFactory.getClass().getName());
    }

    @Override
    public ScriptEngine createEngine(ScriptEngineFactory engineFactory, MangoScript script) {
        ScriptEngine engine;

        if (permissionService.hasAdminRole(script)) {
            engine = engineFactory.getScriptEngine();
        } else {
            // deny access to all java classes
            engine = ((NashornScriptEngineFactory) engineFactory).getScriptEngine(name -> false);
        }

        Bindings engineBindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        JSObject loadFn = (JSObject) engineBindings.get("load");

        if (!permissionService.hasAdminRole(script)) {
            // remove exit and quit functions from bindings
            for (String key : KEYS_TO_REMOVE) {
                engineBindings.remove(key);
            }

            // make the engine and context inaccessible
            try {
                engine.eval("Object.defineProperty(this, 'engine', {}); Object.defineProperty(this, 'context', {});");
            } catch (ScriptException e) {
                throw new RuntimeException(e);
            }
        }

        Function<Object, Object> load = source -> {
            URL url = null;
            if (source instanceof URL) {
                url = (URL) source;
            } else if (source instanceof String && ((String) source).indexOf(':') >= 0) {
                try {
                    url = new URL((String) source);
                } catch (MalformedURLException e) {
                }
            }

            if (url != null) {
                String protocol = url.getProtocol();
                boolean isFileStore = "filestore".equals(protocol);
                boolean isWeb = "http".equals(protocol) || "https".equals(protocol);

                if (isFileStore && permissionService.hasPermission(script, loadFileStorePermission.getPermission()) ||
                        isWeb && permissionService.hasPermission(script, loadWebPermission.getPermission())) {
                    return loadFn.call(null, source);
                }
            }

            permissionService.ensurePermission(script, loadOtherPermission.getPermission());
            return loadFn.call(null, source);
        };
        engineBindings.put("load", load);

        return engine;
    }

    @Override
    public MangoPermission requiredPermission() {
        return permission.getPermission();
    }

}
