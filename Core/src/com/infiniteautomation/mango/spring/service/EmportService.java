/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.infiniteautomation.mango.emport.ImportTask;
import com.infiniteautomation.mango.util.ConfigurationExportData;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.json.JsonException;
import com.serotonin.json.JsonWriter;
import com.serotonin.json.type.JsonObject;
import com.serotonin.json.type.JsonTypeWriter;
import com.serotonin.json.type.JsonValue;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.i18n.Translations;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.util.ProgressiveTaskListener;

/**
 * Service to facilitate JSON import/export
 * @author Terry Packer
 *
 */
@Service
public class EmportService {

    private final RoleService roleService;
    private final UsersService usersService;
    private final MailingListService mailingListService;
    private final DataSourceService dataSourceService;
    private final DataPointService dataPointService;
    private final PublisherService publisherService;
    private final EventHandlerService eventHandlerService;
    private final JsonDataService jsonDataService;
    private final PermissionService permissionService;
    private final EventDetectorsService eventDetectorService;
    private final SystemPermissionService systemPermissionService;

    @Autowired
    public EmportService(RoleService roleService,
            UsersService usersService,
            MailingListService mailingListService,
            DataSourceService dataSourceService,
            DataPointService dataPointService,
            PublisherService publisherService,
            EventHandlerService eventHandlerService,
            JsonDataService jsonDataService,
            PermissionService permissionService,
            EventDetectorsService eventDetectorService,
            SystemPermissionService systemPermissionService) {
        this.roleService = roleService;
        this.usersService = usersService;
        this.mailingListService = mailingListService;
        this.dataSourceService = dataSourceService;
        this.dataPointService = dataPointService;
        this.publisherService = publisherService;
        this.eventHandlerService = eventHandlerService;
        this.jsonDataService = jsonDataService;
        this.permissionService = permissionService;
        this.eventDetectorService = eventDetectorService;
        this.systemPermissionService = systemPermissionService;
    }

    /**
     * Create an import task to import the root JSON object
     * @param root
     * @return
     */
    // TODO Mango 4.0 what is this here for? Why is a user passed through? Add ensureAdminRole() inside here?
    public ImportTask getImportTask(JsonObject root, ProgressiveTaskListener listener, boolean schedule, Translations translations, PermissionHolder user) {
        return new ImportTask(root,
                translations,
                user,
                roleService,
                usersService,
                mailingListService,
                dataSourceService,
                dataPointService,
                publisherService,
                eventHandlerService,
                jsonDataService,
                eventDetectorService,
                systemPermissionService,
                listener, schedule);
    }

    /**
     * Export JSON as a String
     * @param prettyIndent
     * @param exportElements
     * @return
     * @throws PermissionException
     */
    public String createExportData(int prettyIndent, String[] exportElements) throws PermissionException {
        permissionService.ensureAdminRole(Common.getUser());

        Map<String, Object> data = ConfigurationExportData.createExportDataMap(exportElements);
        StringWriter stringWriter = new StringWriter();
        export(data, stringWriter, prettyIndent);
        return stringWriter.toString();
    }

    public String export(Map<String, Object> data, int prettyIndent) {
        StringWriter stringWriter = new StringWriter();
        export(data, stringWriter, prettyIndent);
        return stringWriter.toString();
    }

    /**
     * Export JSON to a Writer
     * @param data
     * @param prettyIndent
     * @return
     * @throws PermissionException
     */
    public void export(Map<String, Object> data, Writer writer, int prettyIndent) throws PermissionException {
        permissionService.ensureAdminRole(Common.getUser());

        JsonTypeWriter typeWriter = new JsonTypeWriter(Common.JSON_CONTEXT);
        JsonWriter jsonWriter = new JsonWriter(Common.JSON_CONTEXT, writer);
        jsonWriter.setPrettyIndent(prettyIndent);
        jsonWriter.setPrettyOutput(prettyIndent > 0);

        try {
            JsonValue export = typeWriter.writeObject(data);
            jsonWriter.writeObject(export);
        }
        catch (JsonException e) {
            throw new ShouldNeverHappenException(e);
        }
        catch (IOException e) {
            throw new ShouldNeverHappenException(e);
        }
    }
}
