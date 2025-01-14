package com.zmops.iot.web.device.service.work;


import com.zmops.iot.domain.device.Device;
import com.zmops.iot.domain.device.query.QDevice;
import com.zmops.iot.util.ToolUtil;
import com.zmops.zeus.server.async.callback.IWorker;
import com.zmops.zeus.server.async.wrapper.WorkerWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author yefei
 * <p>
 * 更新设备 在线状态
 */
@Slf4j
@Component
public class DeviceEventLogWorker implements IWorker<Map<String, String>, Boolean> {


    @Override
    public Boolean action(Map<String, String> object, Map<String, WorkerWrapper> allWrappers) {
        log.debug("insert into event log…………");

        String deviceId = object.get("hostname");
        if (ToolUtil.isEmpty(deviceId)) {
            return false;
        }
        Device device = new QDevice().deviceId.eq(deviceId).findOne();
        if (null == device) {
            return false;
        }

        return true;
    }


    @Override
    public Boolean defaultValue() {
        return true;
    }

}
