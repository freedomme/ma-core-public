/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.module;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.OrderComparator;

import com.github.zafarkhaja.semver.Version;
import com.infiniteautomation.mango.util.exception.ModuleUpgradeException;
import com.serotonin.db.spring.ExtendedJdbcTemplate;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.Constants;
import com.serotonin.m2m2.UpgradeVersionState;
import com.serotonin.m2m2.db.dao.InstalledModulesDao;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.ModuleRegistry.CoreModule;
import com.serotonin.m2m2.util.ExportCodes;
import com.serotonin.m2m2.util.license.LicenseFeature;
import com.serotonin.m2m2.util.license.ModuleLicense;

/**
 * All information regarding a module required by the core.
 *
 * @author Matthew Lohbihler
 */
public class Module {

    private static final Log LOG = LogFactory.getLog(Module.class);

    public static final String MODULE_DATA_ENV_PROP = "moduleData.location";
    public static final String MODULE_DATA_ENV_PROP_DEFAULT = "data";
    public static final String RESOURCES_DIRECTORY = "resources";
    public static final URI MODULES_BASE_URI = URI.create("/" + Constants.DIR_MODULES + "/");

    public static final ExportCodes VERSION_STATE_CODES = new ExportCodes();
    static {
        VERSION_STATE_CODES.addElement(UpgradeVersionState.DEVELOPMENT, "DEVELOPMENT");
        VERSION_STATE_CODES.addElement(UpgradeVersionState.ALPHA, "ALPHA");
        VERSION_STATE_CODES.addElement(UpgradeVersionState.BETA, "BETA");
        VERSION_STATE_CODES.addElement(UpgradeVersionState.RELEASE_CANDIDATE, "RELEASE_CANDIDATE");
        VERSION_STATE_CODES.addElement(UpgradeVersionState.PRODUCTION, "PRODUCTION");
    }

    public static final void sortByName(List<Module> modules) {
        Collections.sort(modules, new Comparator<Module>() {
            @Override
            public int compare(Module m1, Module m2) {
                //Always put Core first
                if(m1 instanceof CoreModule) {
                    return 1;
                }else if(m2 instanceof CoreModule) {
                    return -1;
                }else {
                    return m1.getName().compareTo(m2.getName());
                }
            }
        });
    }

    private final String name;
    private final Version version;
    private Version previousVersion;
    private String licenseType;
    private final TranslatableMessage description;
    private final String vendor;
    private final String vendorUrl;
    private final String dependencies;
    private final int loadOrder;
    private boolean markedForDeletion;

    private final List<ModuleElementDefinition> definitions = new ArrayList<>();

    private final Set<String> locales = new HashSet<>();
    private List<String> emailTemplateDirs;
    private final boolean signed;

    /**
     * Module constructor. Should not be used by client code.
     *
     * @param name
     * @param version
     * @param description
     * @param vendor
     * @param vendorUrl
     */
    public Module(String name, Version version, TranslatableMessage description, String vendor, String vendorUrl,
            String dependencies, int loadOrder, boolean signed) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.vendor = vendor;
        this.vendorUrl = vendorUrl;
        this.dependencies = dependencies;
        this.loadOrder = loadOrder;
        this.signed = signed;
    }

    /**
     * Loads all the module element definition classes and uses ConditionalDefinition to filter them. Does not return any definitions from parent classloaders.
     * @param classloader
     */
    public void loadDefinitions(ClassLoader classLoader) {
        try {
            for (Class<? extends ModuleElementDefinition> defClass : ModuleElementDefinition.loadDefinitions(classLoader, name)) {
                Class<?> clazz = classLoader.loadClass(defClass.getName());
                addDefinition(ModuleElementDefinition.class.cast(clazz.newInstance()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load definitions for module " + name, e);
        }
    }

    /**
     * <p>Suitable for creating URLs to module assets.</p>
     *
     * @return relative URI from MA web root to the module's web directory
     */
    public URI webPath() {
        return MODULES_BASE_URI.resolve(name + "/");
    }

    /**
     * <p>Suitable for creating File objects within Java code.</p>
     *
     * @return absolute path to the module's installation directory
     */
    public Path modulePath() {
        return Common.MA_HOME_PATH.resolve(Constants.DIR_WEB).resolve(Constants.DIR_MODULES).resolve(name);
    }

    public Path resourcesPath() {
        return modulePath().resolve(RESOURCES_DIRECTORY);
    }

    /**
     * @return absolute path to the module's data directory
     */
    public Path moduleDataPath() {
        String location = Common.envProps.getString(MODULE_DATA_ENV_PROP, MODULE_DATA_ENV_PROP_DEFAULT);
        Path dataPath = Common.MA_HOME_PATH.resolve(location).resolve(name);

        try {
            Files.createDirectories(dataPath);
        } catch (IOException e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Error creating module data directory, subsequent read/write operations to this directory will fail", e);
            }
        }

        return dataPath;
    }

    /**
     * Called after the system is initialized, i.e. once services like the database, timer, properties, runtime, etc are
     * available. Should not be used by client code.
     */
    public void preInitialize() {
        for (ModuleElementDefinition df : definitions)
            df.preInitialize();
    }

    /**
     * Called after the database is initialized to perform any database related upgrades outside
     *  of a schema definition
     */
    public boolean upgrade() throws Exception {
        this.previousVersion = InstalledModulesDao.instance.getModuleVersion(name);
        if(this.previousVersion == null) {
            return true;
        }
        try {
            ExtendedJdbcTemplate ejt = new ExtendedJdbcTemplate();
            ejt.setDataSource(Common.databaseProxy.getDataSource());

            for (ModuleElementDefinition df : definitions) {
                if(df instanceof UpgradeDefinition) {
                    ((UpgradeDefinition)df).ejt = ejt;
                    ((UpgradeDefinition)df).upgrade(this.previousVersion, this.version);
                }
            }
            //Fully running so update our version
            InstalledModulesDao.instance.updateModuleVersion(this);
        }catch(Throwable t) {
            //TODO Mango 4.0 unload module here
            throw new ModuleUpgradeException(t, name, previousVersion, version);
        }
        return false;
    }

    /**
     * Called after the database is initialized, but before the event and runtime managers. Should not be
     * used by client code.
     * @return true if module is new
     */
    public void postDatabase() {
        for (ModuleElementDefinition df : definitions)
            df.postDatabase(this.previousVersion, this.version);
    }

    /**
     * Called after immediately after the event manager is initialized, but before the runtime managers. Should not be
     * used by client code.
     */
    public void postEventManager() {
        for (ModuleElementDefinition df : definitions)
            df.postEventManager(this.previousVersion, this.version);
    }

    /**
     * Called after the system is initialized, i.e. once services like the database, timer, properties, runtime, etc are
     * available. Should not be used by client code.
     */
    public void postInitialize() {
        for (ModuleElementDefinition df : definitions)
            df.postInitialize(this.previousVersion, this.version);
    }

    /**
     * Called before the system is terminated, i.e. while services are still available. Should not be used by client
     * code.
     */
    public void preTerminate(boolean uninstall) {
        for (int i = definitions.size() - 1; i >= 0; i--) {
            ModuleElementDefinition df = definitions.get(i);
            df.preTerminate(uninstall);
        }
    }

    /**
     * Called upon shutdown after the runtime, but before the event manager, has been terminated.  Should not be used by client code.
     */
    public void postRuntimeManagerTerminate(boolean uninstall) {
        for (int i = definitions.size() - 1; i >= 0; i--) {
            ModuleElementDefinition df = definitions.get(i);
            df.postRuntimeManagerTerminate(uninstall);
        }
    }

    /**
     * Called after the system is terminated. Should not be used by client code.
     */
    public void postTerminate(boolean uninstall) {
        for (int i = definitions.size() - 1; i >= 0; i--) {
            ModuleElementDefinition df = definitions.get(i);
            df.postTerminate(uninstall);
        }
    }

    /**
     * @return the module's name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the module's version
     */
    public Version getVersion() {
        return version;
    }

    public String getNormalVersion() {
        return version.getNormalVersion();
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public TranslatableMessage getDescription() {
        return description;
    }

    public String getVendor() {
        return vendor;
    }

    public String getVendorUrl() {
        return vendorUrl;
    }

    public String getDependencies() {
        return dependencies;
    }

    public int getLoadOrder() {
        return loadOrder;
    }

    public String getBuildNumber(){
        return this.version.getBuildMetadata();
    }

    public boolean isSigned() {
        return signed;
    }

    public ModuleLicense license() {
        if (Common.license() == null)
            return null;
        return Common.license().getModuleLicense(name);
    }

    public LicenseFeature licenseFeature(String name) {
        ModuleLicense moduleLicense = license();
        if (moduleLicense != null)
            return moduleLicense.getFeature(name);
        return null;
    }

    public boolean isMarkedForDeletion() {
        return markedForDeletion;
    }

    public void setMarkedForDeletion(boolean markedForDeletion) {
        if(this.markedForDeletion) { //We need to loop over all the dependencies none are marked to delete
            if (!StringUtils.isBlank(dependencies)) {
                String[] parts = dependencies.split("\\s*,\\s*");

                for (String dependencyStr : parts) {
                    if (dependencyStr.isEmpty()) continue;
                    String depName;

                    int pos = dependencyStr.indexOf(':');
                    if (pos == -1)
                        depName = dependencyStr;
                    else
                        depName = dependencyStr.substring(0, pos);

                    Module dep = ModuleRegistry.getModule(depName);
                    if(dep == null)
                        LOG.error("Unable to identify module dependency: " + dep); //don't let this stop us
                    else if(dep.isMarkedForDeletion()) { //Okay, this will stop us.
                        LOG.warn("Cannot unmark module " + name + " for deletion while its dependencies are marked for deletion: " + depName);
                        this.markedForDeletion = true;
                        return;
                    }
                }
            }
        } else {
            //We need to check if any modules depend on this one.
            for(Module mod : ModuleRegistry.getModules()) {
                if(mod.getName().equals(name) || StringUtils.isBlank(mod.getDependencies()))
                    continue;

                String[] parts = mod.getDependencies().split("\\s*,\\s*");
                for (String dependencyStr : parts) {
                    if (dependencyStr.isEmpty()) continue;
                    String depName;

                    int pos = dependencyStr.indexOf(':');
                    if (pos == -1)
                        depName = dependencyStr;
                    else
                        depName = dependencyStr.substring(0, pos);

                    if(name.equals(depName) && !mod.isMarkedForDeletion()) {
                        LOG.warn("Cannot mark module " + name + " for deletion while something depends on it: " + depName);
                        this.markedForDeletion = false;
                        return;
                    }
                }
            }
        }
        this.markedForDeletion = markedForDeletion;
    }

    public void addDefinition(ModuleElementDefinition definition) {
        definition.setModule(this);
        definitions.add(definition);
        definitions.sort(OrderComparator.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    public <T extends ModuleElementDefinition> List<T> getDefinitions(Class<T> clazz) {
        List<T> defs = new ArrayList<>();
        for (ModuleElementDefinition def : definitions) {
            if (clazz.isAssignableFrom(def.getClass()))
                defs.add((T) def);
        }
        return defs;
    }

    public <T extends ModuleElementDefinition> T getDefinition(Class<T> clazz) {
        List<T> defs = getDefinitions(clazz);
        if (defs.isEmpty()) {
            return null;
        }
        return defs.get(0);
    }

    public List<TranslatableMessage> getLicenseErrors() {
        List<TranslatableMessage> errors = new ArrayList<>();
        for (LicenseDefinition def : getDefinitions(LicenseDefinition.class))
            def.addLicenseErrors(errors);
        return errors;
    }

    public List<TranslatableMessage> getLicenseWarnings() {
        List<TranslatableMessage> warnings = new ArrayList<>();
        for (LicenseDefinition def : getDefinitions(LicenseDefinition.class))
            def.addLicenseWarnings(warnings);
        return warnings;
    }

    public void addLocaleDefinition(String locale) {
        locales.add(locale);
    }

    public Set<String> getLocales() {
        return locales;
    }

    public List<String> getEmailTemplateDirs() {
        return emailTemplateDirs;
    }

    public void setEmailTemplateDirs(List<String> emailTemplates) {
        this.emailTemplateDirs = emailTemplates;
    }
}
