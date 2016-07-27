/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package controllers.catalog;

import static com.emc.vipr.client.core.util.ResourceUtils.uri;
import static controllers.Common.angularRenderArgs;

import java.util.Calendar;
import java.util.List;

import models.datatable.ScheduledOrdersDataTable;
import models.datatable.ScheduledOrdersDataTable.ScheduledOrderInfo;

import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.data.binding.As;
import play.data.validation.Validation;
import play.mvc.Controller;
import play.mvc.Util;
import play.mvc.With;
import util.CatalogServiceUtils;
import util.ExecutionWindowUtils;
import util.OrderUtils;
import util.datatable.DataTableParams;
import util.datatable.DataTablesSupport;

import com.emc.vipr.model.catalog.ExecutionWindowRestRep;
import com.emc.vipr.model.catalog.OrderRestRep;
import com.emc.vipr.model.catalog.ScheduleCycleType;
import com.emc.vipr.model.catalog.ScheduleInfo;
import com.google.common.collect.Lists;

import controllers.Common;
import controllers.catalog.Orders.OrderDetails;
import controllers.deadbolt.Restrict;
import controllers.deadbolt.Restrictions;
import controllers.tenant.TenantSelector;
import controllers.util.FlashException;
import controllers.util.Models;

@With(Common.class)
@Restrictions({ @Restrict("TENANT_ADMIN") })
public class ScheduledOrders extends Controller {

    protected static final String CANCELLED = "ScheduledOrder.cancel.success";

    @Util
    public static void addNextExecutionWindow() {
        Calendar now = Calendar.getInstance();
        ExecutionWindowRestRep nextWindow = ExecutionWindowUtils.getNextExecutionWindow(now);
        if (nextWindow != null) {
            Calendar nextWindowTime = ExecutionWindowUtils.calculateNextWindowTime(now, nextWindow);
            renderArgs.put("nextWindowName", nextWindow.getName());
            renderArgs.put("nextWindowTime", nextWindowTime.getTime());
        }
    }

    public static void list() {
        addNextExecutionWindow();
        renderArgs.put("dataTable", new ScheduledOrdersDataTable());
        TenantSelector.addRenderArgs();
        render();
    }

    public static void listJson() {
        DataTableParams dataTableParams = DataTablesSupport.createParams(params);
        ScheduledOrdersDataTable dataTable = new ScheduledOrdersDataTable();
        renderJSON(DataTablesSupport.createJSON(dataTable.fetchData(dataTableParams), params));
    }

    public static void itemsJson(@As(",") String[] ids) {
        List<ScheduledOrderInfo> results = Lists.newArrayList();
        if (ids != null && ids.length > 0) {
            for (String id : ids) {
                if (StringUtils.isNotBlank(id)) {
                    OrderRestRep order = OrderUtils.getOrder(uri(id));
                    if (order != null) {
                        Models.checkAccess(order.getTenant());
                        results.add(new ScheduledOrderInfo(order));
                    }
                }
            }
        }
        renderJSON(DataTablesSupport.toJson(results));
    }

    public static void cancel(@As(",") String[] ids) {
        if ((ids != null) && (ids.length > 0)) {
            Logger.info("Cancel: " + StringUtils.join(ids, ", "));

            for (String orderId : ids) {
                if (StringUtils.isNotBlank(orderId)) {
                    OrderUtils.cancelOrder(uri(orderId));
                }
            }
        }
        list();
    }

    public static void showOrder(String orderId) {
        redirect("Orders.receipt", orderId);
    }
    
    @FlashException("list")
    public static void edit(String id) {
        OrderDetails details = new OrderDetails(id);
        details.catalogService = CatalogServiceUtils.getCatalogService(details.order.getCatalogService());
        
        ScheduleEventForm form = new ScheduleEventForm(details);
        angularRenderArgs().put("scheduler", form);
        render(form);
    }
    
    @FlashException(keep = true, referrer = { "edit" })
    public static void save(ScheduleEventForm scheduler) {
        scheduler.validate("scheduler");
        Logger.info(scheduler.startDate);
        if (Validation.hasErrors()) {
            Common.handleError();
        }
        list();
    }
    
    public static class ScheduleEventForm {
        public String id;
        public String name;
        public String startDate;
        public String startTime;
        public Integer recurrence;
        public Integer rangeOfRecurrence;
        public String cycleType;
        public Integer cycleFrequency;
        public Integer dayOfMonth;
        public Integer dayOfWeek;
        public Boolean recurringAllowed;
        
        public ScheduleEventForm(OrderDetails details) {
            id = details.order.getId().toString();
            recurringAllowed = details.catalogService.isRecurringAllowed();
            if (details.order.getScheduledEventId() != null) {
                ScheduleInfo schedulerInfo = details.getScheduledEvent().getScheduleInfo();
                startDate = schedulerInfo.getStartDate();
                startTime = String.format("%02d:%02d", schedulerInfo.getHourOfDay(), schedulerInfo.getMinuteOfHour());
                recurrence = schedulerInfo.getReoccurrence();
                if (recurrence > 1) {
                    rangeOfRecurrence = recurrence;
                    recurrence = -1;
                } else {
                    rangeOfRecurrence = 1;
                }
                
                cycleType = schedulerInfo.getCycleType().toString();
                cycleFrequency = schedulerInfo.getCycleFrequency();
                if (schedulerInfo.getCycleType() == ScheduleCycleType.MONTHLY) {
                    dayOfMonth = Integer.parseInt(schedulerInfo.getSectionsInCycle().get(0));
                    dayOfWeek = 1;
                } else if (schedulerInfo.getCycleType() == ScheduleCycleType.WEEKLY) {
                    dayOfWeek = Integer.parseInt(schedulerInfo.getSectionsInCycle().get(0));
                    dayOfMonth = 1;
                } else {
                    dayOfMonth = 1;
                    dayOfWeek = 1;
                }
            }
        }
        public ScheduleEventForm() {
            
        }
        public void validate(String fieldName) {
            Validation.valid(fieldName, this);
        }
    }
}
