package com.zmops.iot.web.device.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dtflys.forest.Forest;
import com.zmops.iot.async.executor.Async;
import com.zmops.iot.async.wrapper.WorkerWrapper;
import com.zmops.iot.core.auth.context.LoginContextHolder;
import com.zmops.iot.domain.product.*;
import com.zmops.iot.domain.product.query.QProductEvent;
import com.zmops.iot.domain.product.query.QProductEventExpression;
import com.zmops.iot.domain.product.query.QProductEventRelation;
import com.zmops.iot.domain.product.query.QProductEventService;
import com.zmops.iot.enums.CommonStatus;
import com.zmops.iot.enums.InheritStatus;
import com.zmops.iot.model.exception.ServiceException;
import com.zmops.iot.model.page.Pager;
import com.zmops.iot.util.DefinitionsUtil;
import com.zmops.iot.util.ToolUtil;
import com.zmops.iot.web.device.dto.DeviceEventRelationDto;
import com.zmops.iot.web.device.dto.MultipleDeviceEventDto;
import com.zmops.iot.web.device.dto.MultipleDeviceEventRule;
import com.zmops.iot.web.device.dto.MultipleDeviceServiceDto;
import com.zmops.iot.web.device.dto.param.MultipleDeviceEventParm;
import com.zmops.iot.web.device.service.work.DeviceServiceLogWorker;
import com.zmops.iot.web.device.service.work.ScenesLogWorker;
import com.zmops.iot.web.exception.enums.BizExceptionEnum;
import com.zmops.iot.web.product.dto.ProductEventRuleDto;
import com.zmops.zeus.driver.service.ZbxTrigger;
import io.ebean.DB;
import io.ebean.annotation.Transactional;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author yefei
 **/
@Service
public class MultipleDeviceEventRuleService {

    @Autowired
    private ZbxTrigger zbxTrigger;

    @Autowired
    DeviceServiceLogWorker deviceServiceLogWorker;

    @Autowired
    ScenesLogWorker scenesLogWorker;

    /**
     * 设备联动 分页列表
     *
     * @param eventParm
     * @return
     */
    public Pager<MultipleDeviceEventDto> getEventByPage(MultipleDeviceEventParm eventParm) {
        QProductEvent query = new QProductEvent();

        if (ToolUtil.isNotEmpty(eventParm.getEventRuleName())) {
            query.eventRuleName.contains(eventParm.getEventRuleName());
        }
        if (ToolUtil.isNotEmpty(eventParm.getClassify())) {
            query.classify.eq(eventParm.getClassify());
        }

        List<MultipleDeviceEventDto> list = query.setFirstRow((eventParm.getPage() - 1) * eventParm.getMaxRow())
                .setMaxRows(eventParm.getMaxRow()).orderBy(" create_time desc").asDto(MultipleDeviceEventDto.class).findList();

        if (ToolUtil.isEmpty(list)) {
            return new Pager<>(list, 0);
        }

        //关联状态 备注 触发设备
        List<Long> eventRuleIds = list.parallelStream().map(MultipleDeviceEventDto::getEventRuleId).collect(Collectors.toList());
        String sql = "SELECT " +
                " pr.event_rule_id, " +
                " array_to_string( ARRAY_AGG ( d.NAME ), ',' ) triggerDevice,  " +
                " pr.status," +
                " pr.remark " +
                "FROM " +
                " product_event_relation pr " +
                " LEFT JOIN device d ON pr.relation_id = d.device_id  " +
                "WHERE " +
                " pr.event_rule_id IN (:eventRuleIds) " +
                "GROUP BY " +
                " pr.event_rule_id,pr.status,pr.remark";
        List<DeviceEventRelationDto> deviceEventRelationDtos = DB.findDto(DeviceEventRelationDto.class, sql).setParameter("eventRuleIds", eventRuleIds).findList();
        Map<Long, DeviceEventRelationDto> productEventRelationMap = deviceEventRelationDtos.parallelStream()
                .collect(Collectors.toConcurrentMap(DeviceEventRelationDto::getEventRuleId, o -> o));

        //关联 执行设备
        sql = "SELECT " +
                " ps.event_rule_id, " +
                " array_to_string( ARRAY_AGG ( d.NAME ), ',' ) executeDevice " +
                "FROM " +
                " product_event_service ps " +
                " LEFT JOIN device d ON ps.execute_device_id = d.device_id  " +
                "WHERE " +
                " ps.event_rule_id IN (:eventRuleIds) " +
                "GROUP BY " +
                " ps.event_rule_id";
        List<MultipleDeviceServiceDto> deviceServiceDtos = DB.findDto(MultipleDeviceServiceDto.class, sql).setParameter("eventRuleIds", eventRuleIds).findList();
        Map<Long, MultipleDeviceServiceDto> deviceServiceMap = deviceServiceDtos.parallelStream()
                .collect(Collectors.toConcurrentMap(MultipleDeviceServiceDto::getEventRuleId, o -> o));

        list.forEach(productEventDto -> {
            DeviceEventRelationDto deviceEventRelationDto = productEventRelationMap.get(productEventDto.getEventRuleId());
            if (null != deviceEventRelationDto) {
                String status = deviceEventRelationDto.getStatus();
                String remark = deviceEventRelationDto.getRemark();
                String triggerDevice = deviceEventRelationDto.getTriggerDevice();
                productEventDto.setStatus(status);
                productEventDto.setRemark(remark);
                productEventDto.setTriggerDevice(triggerDevice);
            }
            MultipleDeviceServiceDto deviceServiceDto = deviceServiceMap.get(productEventDto.getEventRuleId());
            if (null != deviceServiceDto) {
                productEventDto.setExecuteDevice(deviceServiceDto.getExecuteDevice());
            }
        });

        return new Pager<>(list, query.findCount());
    }

    /**
     * 保存设备联动
     *
     * @param eventRuleId 设备联动ID
     * @param eventRule   设备联动规则
     */
    @Transactional(rollbackFor = Exception.class)
    public void createDeviceEventRule(Long eventRuleId, MultipleDeviceEventRule eventRule) {
        // step 1: 保存产品告警规则
        ProductEvent event = initEventRule(eventRule);
        event.setEventRuleId(eventRuleId);
        DB.save(event);

        //step 2: 保存 表达式，方便回显
        List<ProductEventExpression> expList = new ArrayList<>();

        eventRule.getExpList().forEach(i -> {
            ProductEventExpression exp = initEventExpression(i);
            exp.setEventRuleId(eventRuleId);
            expList.add(exp);
        });

        DB.saveAll(expList);

        //step 3: 保存触发器 调用 本产品方法
        if (null != eventRule.getDeviceServices() && !eventRule.getDeviceServices().isEmpty()) {
            eventRule.getDeviceServices().forEach(i -> {
                DB.sqlUpdate("insert into product_event_service(event_rule_id, execute_device_id, service_id) " +
                        "values (:eventRuleId, :executeDeviceId, :serviceId)")
                        .setParameter("eventRuleId", eventRuleId)
                        .setParameter("executeDeviceId", i.getExecuteDeviceId())
                        .setParameter("serviceId", i.getServiceId())
                        .execute();
            });
        }

        //step 4: 保存关联关系
        List<String> relationIds = eventRule.getExpList().parallelStream().map(MultipleDeviceEventRule.Expression::getDeviceId).distinct().collect(Collectors.toList());
        if (ToolUtil.isEmpty(relationIds)) {
            throw new ServiceException(BizExceptionEnum.EVENT_HAS_NOT_DEVICE);
        }
        List<ProductEventRelation> productEventRelationList = new ArrayList<>();
        relationIds.forEach(relationId -> {
            ProductEventRelation productEventRelation = new ProductEventRelation();
            productEventRelation.setEventRuleId(eventRuleId);
            productEventRelation.setRelationId(relationId);
            productEventRelation.setInherit(InheritStatus.NO.getCode());
            productEventRelation.setStatus(CommonStatus.ENABLE.getCode());
            productEventRelation.setRemark(eventRule.getRemark());
            productEventRelationList.add(productEventRelation);
        });
        DB.saveAll(productEventRelationList);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDeviceEventRule(Long eventRuleId, MultipleDeviceEventRule eventRule) {

        //step 1: 函数表达式
        DB.sqlUpdate("delete from product_event_expression where event_rule_id = :eventRuleId")
                .setParameter("eventRuleId", eventRule.getEventRuleId())
                .execute();

        //step 2: 删除服务方法调用
        DB.sqlUpdate("delete from product_event_service where event_rule_id = :eventRuleId")
                .setParameter("eventRuleId", eventRule.getEventRuleId())
                .execute();

        // 删除和所有设备的关联关系
        new QProductEventRelation().eventRuleId.eq(eventRule.getEventRuleId()).delete();

        // step 3: 保存产品告警规则
        ProductEvent event = initEventRule(eventRule);
        event.setEventRuleId(eventRuleId);
        event.update();

        //step 4: 保存 表达式，方便回显
        List<ProductEventExpression> expList = new ArrayList<>();

        eventRule.getExpList().forEach(i -> {
            ProductEventExpression exp = initEventExpression(i);
            exp.setEventRuleId(eventRuleId);
            expList.add(exp);
        });

        DB.saveAll(expList);

        //step 5: 保存触发器 调用 本产品方法
        if (null != eventRule.getDeviceServices() && !eventRule.getDeviceServices().isEmpty()) {
            eventRule.getDeviceServices().forEach(i -> {
                DB.sqlUpdate("insert into product_event_service(event_rule_id,execute_device_id, service_id) values (:eventRuleId, :executeDeviceId, :serviceId)")
                        .setParameter("eventRuleId", eventRuleId)
                        .setParameter("executeDeviceId", i.getExecuteDeviceId())
                        .setParameter("serviceId", i.getServiceId())
                        .execute();
            });
        }

        // step 6: 保存关联关系
        List<String> relationIds = eventRule.getExpList().parallelStream().map(MultipleDeviceEventRule.Expression::getDeviceId).distinct().collect(Collectors.toList());
        if (ToolUtil.isEmpty(relationIds)) {
            throw new ServiceException(BizExceptionEnum.EVENT_HAS_NOT_DEVICE);
        }
        List<ProductEventRelation> productEventRelationList = new ArrayList<>();
        relationIds.forEach(relationId -> {
            ProductEventRelation productEventRelation = new ProductEventRelation();
            productEventRelation.setEventRuleId(eventRuleId);
            productEventRelation.setRelationId(relationId);
            productEventRelation.setStatus(CommonStatus.ENABLE.getCode());
            productEventRelation.setRemark(eventRule.getRemark());
            productEventRelationList.add(productEventRelation);
        });
        DB.saveAll(productEventRelationList);
    }


    private ProductEvent initEventRule(MultipleDeviceEventRule eventRule) {
        ProductEvent event = new ProductEvent();
        event.setEventLevel(eventRule.getEventLevel().toString());
        event.setExpLogic(eventRule.getExpLogic());
        event.setEventNotify(eventRule.getEventNotify().toString());
        event.setClassify(eventRule.getClassify());
        event.setEventRuleName(eventRule.getEventRuleName());
        return event;
    }

    private ProductEventExpression initEventExpression(MultipleDeviceEventRule.Expression exp) {
        ProductEventExpression eventExpression = new ProductEventExpression();
        eventExpression.setEventExpId(exp.getEventExpId());
        eventExpression.setCondition(exp.getCondition());
        eventExpression.setFunction(exp.getFunction());
        eventExpression.setScope(exp.getScope());
        eventExpression.setValue(exp.getValue());
        eventExpression.setDeviceId(exp.getDeviceId());
        eventExpression.setProductAttrKey(exp.getProductAttrKey());
        eventExpression.setProductAttrId(exp.getProductAttrId());
        eventExpression.setProductAttrType(exp.getProductAttrType());
        eventExpression.setPeriod(exp.getPeriod());
        eventExpression.setUnit(exp.getUnit());
        eventExpression.setAttrValueType(exp.getAttrValueType());
        return eventExpression;
    }

    /**
     * 更新 设备联动规则 zbxId
     *
     * @param triggerId 设备联动ID
     * @param zbxId     triggerId
     */
    public void updateProductEventRuleZbxId(Long triggerId, String[] zbxId) {
        DB.update(ProductEventRelation.class).where().eq("eventRuleId", triggerId)
                .asUpdate().set("zbxId", zbxId[0]).update();
    }

    /**
     * 获取设备联动详情
     *
     * @param productEvent
     * @param eventRuleId
     * @return
     */
    public ProductEventRuleDto detail(ProductEvent productEvent, long eventRuleId) {
        ProductEventRuleDto productEventRuleDto = new ProductEventRuleDto();
        ToolUtil.copyProperties(productEvent, productEventRuleDto);

        List<ProductEventExpression> expList = new QProductEventExpression().eventRuleId.eq(eventRuleId).findList();

        productEventRuleDto.setExpList(expList);
        productEventRuleDto.setDeviceServices(new QProductEventService().eventRuleId.eq(eventRuleId).findList());

        ProductEventRelation productEventRelation = new QProductEventRelation().eventRuleId.eq(eventRuleId).findOne();
        productEventRuleDto.setStatus(productEventRelation.getStatus());
        productEventRuleDto.setRemark(productEventRelation.getRemark());
        productEventRuleDto.setInherit(productEventRelation.getInherit());
        if (InheritStatus.YES.getCode().equals(productEventRelation.getInherit())) {
            ProductEventRelation one = new QProductEventRelation().eventRuleId.eq(eventRuleId).inherit.eq(InheritStatus.NO.getCode()).findOne();
            productEventRuleDto.setInheritProductId(one.getRelationId());
        }

        JSONArray triggerInfo = JSONObject.parseArray(zbxTrigger.triggerAndTagsGet(productEventRelation.getZbxId()));
        List<ProductEventRuleDto.Tag> tagList = JSONObject.parseArray(triggerInfo.getJSONObject(0).getString("tags"), ProductEventRuleDto.Tag.class);

        productEventRuleDto.setZbxId(productEventRelation.getZbxId());
        productEventRuleDto.setTags(tagList.stream()
                .filter(s -> !s.getTag().equals("__execute__") && !s.getTag().equals("__alarm__") && !s.getTag().equals("__event__"))
                .collect(Collectors.toList()));

        return productEventRuleDto;
    }

    /**
     * 创建 设备联动
     *
     * @param triggerName 设备联动名称
     * @param expression  表达式
     * @return 触发器ID
     */
    public String[] createZbxTrigger(String triggerName, String expression, Byte level) {
        String res = zbxTrigger.executeTriggerCreate(triggerName, expression, level);
        return JSON.parseObject(res, TriggerIds.class).getTriggerids();
    }

    /**
     * 更新 设备联动
     *
     * @param triggerId
     * @param expression
     * @param level
     * @return
     */
    public String[] updateZbxTrigger(String triggerId, String expression, Byte level) {
        String res = zbxTrigger.triggerUpdate(triggerId, expression, level);
        return JSON.parseObject(res, TriggerIds.class).getTriggerids();
    }

    public void execute(Long eventRuleId,String type,Long userId) {
        Map<String, Object> alarmInfo = new ConcurrentHashMap<>(3);
        alarmInfo.put("eventRuleId", eventRuleId);
        alarmInfo.put("triggerType", type);
        alarmInfo.put("triggerUser", userId);

        WorkerWrapper<Map<String, Object>, Boolean> deviceServiceLogWork = WorkerWrapper.<Map<String, Object>, Boolean>builder().id("deviceServiceLogWorker")
                .worker(deviceServiceLogWorker).param(alarmInfo)
                .build();
        WorkerWrapper<Map<String, Object>, Boolean> scenesLogWork = WorkerWrapper.<Map<String, Object>, Boolean>builder().id("scenesLogWorker")
                .worker(scenesLogWorker).param(alarmInfo)
                .build();

        try {
            Async.work(1000, deviceServiceLogWork, scenesLogWork).awaitFinish();
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ProductEventService> productEventServiceList = new QProductEventService().eventRuleId.eq(eventRuleId)
                .deviceId.isNull().findList();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, List<ProductEventService>> collect = productEventServiceList.parallelStream().collect(Collectors.groupingBy(ProductEventService::getExecuteDeviceId));

        collect.forEach((key, value) -> {
            Map<String, Object> map = new ConcurrentHashMap<>();
            map.put("device", key);

            List<Map<String, Object>> serviceList = new ArrayList<>();
            value.forEach(val -> {
                Map<String, Object> serviceMap = new ConcurrentHashMap<>();
                serviceMap.put("name", DefinitionsUtil.getServiceName(val.getServiceId()));

                List<ProductServiceParam> paramList = DefinitionsUtil.getServiceParam(val.getServiceId());
                if (ToolUtil.isNotEmpty(paramList)) {
                    serviceMap.put("param", paramList.parallelStream().collect(Collectors.toMap(ProductServiceParam::getKey, ProductServiceParam::getValue)));
                }
                serviceList.add(serviceMap);
            });
            map.put("service", serviceList);
            list.add(map);
        });

        Forest.post("/device/action/exec").host("127.0.0.1").port(12800).contentTypeJson().addBody(JSON.toJSON(list)).execute();
    }

    @Data
    static class TriggerIds {
        private String[] triggerids;
    }

    @Data
    public static class Triggers {
        private String      triggerid;
        private String      description;
        private List<Hosts> hosts;
    }

    @Data
    static class Hosts {
        private String hostid;
        private String host;
    }
}
