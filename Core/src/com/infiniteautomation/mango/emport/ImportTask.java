/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.infiniteautomation.mango.emport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.infiniteautomation.mango.spring.service.DataPointService;
import com.infiniteautomation.mango.spring.service.DataSourceService;
import com.infiniteautomation.mango.spring.service.EventDetectorsService;
import com.infiniteautomation.mango.spring.service.EventHandlerService;
import com.infiniteautomation.mango.spring.service.JsonDataService;
import com.infiniteautomation.mango.spring.service.MailingListService;
import com.infiniteautomation.mango.spring.service.PermissionService;
import com.infiniteautomation.mango.spring.service.PublisherService;
import com.infiniteautomation.mango.spring.service.RoleService;
import com.infiniteautomation.mango.spring.service.SystemPermissionService;
import com.infiniteautomation.mango.spring.service.UsersService;
import com.infiniteautomation.mango.util.ConfigurationExportData;
import com.infiniteautomation.mango.util.exception.ValidationException;
import com.serotonin.json.JsonException;
import com.serotonin.json.JsonReader;
import com.serotonin.json.type.JsonArray;
import com.serotonin.json.type.JsonObject;
import com.serotonin.json.type.JsonValue;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.i18n.ProcessResult;
import com.serotonin.m2m2.i18n.Translations;
import com.serotonin.m2m2.module.EmportDefinition;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.util.timeout.ProgressiveTask;
import com.serotonin.m2m2.vo.dataPoint.DataPointWithEventDetectors;
import com.serotonin.m2m2.vo.event.detector.AbstractPointEventDetectorVO;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.util.ProgressiveTaskListener;

/**
 * @author Matthew Lohbihler
 */
public class ImportTask extends ProgressiveTask {

    private static Log LOG = LogFactory.getLog(ImportTask.class);

    protected final ImportContext importContext;
    protected float progress = 0f;
    protected float progressChunk;

    protected final List<Importer> importers = new ArrayList<Importer>();
    protected final List<ImportItem> importItems = new ArrayList<ImportItem>();
    protected final Map<String, DataPointWithEventDetectors> eventDetectorPoints = new HashMap<>();

    protected final DataPointService dataPointService;
    protected final EventDetectorsService eventDetectorService;

    protected PermissionHolder user;
    /**
     * Create an Import task with a listener to be scheduled now
     * @param root
     * @param translations
     * @param user
     * @param listener
     */
    public ImportTask(JsonObject root,
            Translations translations,
            PermissionHolder user,
            RoleService roleService,
            UsersService usersService,
            MailingListService mailingListService,
            DataSourceService dataSourceService,
            DataPointService dataPointService,
            PublisherService publisherService,
            EventHandlerService eventHandlerService,
            JsonDataService jsonDataService,
            EventDetectorsService eventDetectorService,
            SystemPermissionService permissionService,
            ProgressiveTaskListener listener, boolean schedule) {
        super("JSON import task", "JsonImport", 10, listener);
        this.user = user;
        this.dataPointService = dataPointService;
        this.eventDetectorService = eventDetectorService;
        JsonReader reader = new JsonReader(Common.JSON_CONTEXT, root);
        this.importContext = new ImportContext(reader, new ProcessResult(), translations);

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.ROLES))
            addImporter(new RoleImporter(jv.toJsonObject(), roleService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.PERMISSIONS))
            addImporter(new PermissionImporter(jv.toJsonObject(), permissionService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.USERS))
            addImporter(new UserImporter(jv.toJsonObject(), usersService, user));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.DATA_SOURCES))
            addImporter(new DataSourceImporter(jv.toJsonObject(), dataSourceService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.DATA_POINTS))
            addImporter(new DataPointImporter(jv.toJsonObject(), eventDetectorPoints, dataPointService, dataSourceService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.MAILING_LISTS))
            addImporter(new MailingListImporter(jv.toJsonObject(), mailingListService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.PUBLISHERS))
            addImporter(new PublisherImporter(jv.toJsonObject(), publisherService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.EVENT_HANDLERS))
            addImporter(new EventHandlerImporter(jv.toJsonObject(), eventHandlerService));

        JsonObject obj = root.getJsonObject(ConfigurationExportData.SYSTEM_SETTINGS);
        if(obj != null)
            addImporter(new SystemSettingsImporter(obj, user, permissionService, roleService));

        for (JsonValue jv : nonNullList(root, ConfigurationExportData.VIRTUAL_SERIAL_PORTS))
            addImporter(new VirtualSerialPortImporter(jv.toJsonObject()));

        for(JsonValue jv : nonNullList(root, ConfigurationExportData.JSON_DATA))
            addImporter(new JsonDataImporter(jv.toJsonObject(), jsonDataService));

        final String globalScriptId = "sstGlobalScripts";
        List<Importer> globalScriptImporters = new ArrayList<>();
        for (EmportDefinition def : ModuleRegistry.getDefinitions(EmportDefinition.class)) {
            if(globalScriptId.equals(def.getElementId())) {
                JsonValue scripts = root.get(def.getElementId());
                if(scripts != null) {
                    for(JsonValue script : scripts.toJsonArray()) {
                        globalScriptImporters.add(new Importer(null) {
                            @Override
                            protected void importImpl() {
                                try {
                                    def.doImport(script, importContext, user);
                                } catch (JsonException e) {
                                    addException(e);
                                }
                            }
                        });
                    }
                }
            }else {
                ImportItem importItem = new ImportItem(def, root.get(def.getElementId()));
                importItems.add(importItem);
            }
        }

        for(JsonValue jv : nonNullList(root, ConfigurationExportData.EVENT_DETECTORS))
            addImporter(new EventDetectorImporter(jv.toJsonObject(), eventDetectorPoints, dataPointService));

        //Quick hack to ensure the Global Scripts are imported first in case they are used in scripts that will be loaded during this import
        for(Importer importer : globalScriptImporters){
            importers.add(0, importer);
        }

        this.progressChunk = 100f/((float)importers.size() + (float)importItems.size() + 1);  //+1 for processDataPointPaths

        if(schedule)
            Common.backgroundProcessing.execute(this);
    }

    private List<JsonValue> nonNullList(JsonObject root, String key) {
        JsonArray arr = root.getJsonArray(key);
        if (arr == null)
            arr = new JsonArray();
        return arr;
    }

    private void addImporter(Importer importer) {
        importer.setImportContext(importContext);
        importer.setImporters(importers);
        importers.add(importer);
    }

    public ProcessResult getResponse() {
        return importContext.getResult();
    }

    protected int importerIndex;
    protected boolean importerSuccess;
    protected boolean importedItems;

    @Override
    protected void runImpl() {
        Common.getBean(PermissionService.class).runAsSystemAdmin(this::runImplAsAdmin);
    }

    protected void runImplAsAdmin() {
        try {
            if (!importers.isEmpty()) {
                if (importerIndex >= importers.size()) {
                    // A run through the importers has been completed.
                    if (importerSuccess) {
                        // If there were successes with the importers and there are still more to do, run through
                        // them again.
                        importerIndex = 0;
                        importerSuccess = false;
                    } else if(!importedItems) {
                        try {
                            for (ImportItem importItem : importItems) {
                                if (!importItem.isComplete()) {
                                    importItem.importNext(importContext, user);
                                    return;
                                }
                            }
                            importedItems = true;   // We may have imported a dependency in a module
                            importerIndex = 0;
                        }
                        catch (Exception e) {
                            addException(e);
                        }
                    } else {
                        // There are importers left in the list, but there were no successful imports in the last run
                        // of the set. So, all that is left is stuff that will always fail. Copy the validation
                        // messages to the context for each.
                        // Run the import items.
                        for (Importer importer : importers)
                            importer.copyMessages();
                        importers.clear();
                        completed = true;
                        return;
                    }
                }

                // Run the next importer
                Importer importer = importers.get(importerIndex);
                try {
                    importer.doImport();
                    if (importer.success()) {
                        // The import was successful. Note the success and remove the importer from the list.
                        importerSuccess = true;
                        importers.remove(importerIndex);
                    }
                    else{
                        // The import failed. Leave it in the list since the run of another importer
                        // may resolved the problem.
                        importerIndex++;
                    }
                }
                catch (Exception e) {
                    // Uh oh...
                    LOG.error(e.getMessage(),e);
                    addException(e);
                    importers.remove(importerIndex);
                }

                return;
            }

            // Run the import items.
            try {
                for (ImportItem importItem : importItems) {
                    if (!importItem.isComplete()) {
                        importItem.importNext(importContext, user);
                        return;
                    }
                }
                processUpdatedDetectors(eventDetectorPoints);
                completed = true;
            }
            catch (Exception e) {
                addException(e);
            }
        }
        finally {
            //Compute progress, but only declare if we are < 100 since we will declare 100 when done
            //Our progress is 100 - chunk*importersLeft
            int importItemsLeft = 1;
            if(completed)
                importItemsLeft = 0; //Since we know we ran the processDataPointPaths method
            for(ImportItem item : importItems)
                if(!item.isComplete())
                    importItemsLeft++;
            this.progress = 100f - progressChunk*((float)importers.size() + (float)importItemsLeft);
            if(progress < 100f)
                declareProgress(this.progress);
        }
    }

    private void processUpdatedDetectors(Map<String, DataPointWithEventDetectors> eventDetectorMap) {
        for(DataPointWithEventDetectors dp : eventDetectorMap.values()) {
            //The content of the event detectors lists may have duplicates and the DataPointVO may be out of date,
            // but we can assume that all the event detectors for a point will exist in this list.
            for(AbstractPointEventDetectorVO ed : dp.getEventDetectors()) {
                try {
                    if(ed.isNew()) {
                        eventDetectorService.insertAndReload(ed, false);
                        importContext.addSuccessMessage(true, "emport.eventDetector.prefix", ed.getXid());
                    }else {
                        eventDetectorService.updateAndReload(ed.getXid(), ed, false);
                        importContext.addSuccessMessage(false, "emport.eventDetector.prefix", ed.getXid());
                    }

                    //Reload into the RT
                    dataPointService.reloadDataPoint(dp.getDataPoint().getXid());

                }catch(ValidationException e) {
                    importContext.copyValidationMessages(e.getValidationResult(), "emport.eventDetector.prefix", ed.getXid());
                }catch(Exception e) {
                    addException(e);
                    LOG.error("Event detector import failed.", e);
                }
            }
        }

    }

    private void addException(Exception e) {
        String msg = e.getMessage();
        Throwable t = e;
        while ((t = t.getCause()) != null)
            msg += ", " + importContext.getTranslations().translate("emport.causedBy") + " '" + t.getMessage() + "'";
        //We were missing NPE and others without a msg
        if(msg == null)
            msg = e.getClass().getCanonicalName();
        importContext.getResult().addGenericMessage("common.default", msg);
    }
}
