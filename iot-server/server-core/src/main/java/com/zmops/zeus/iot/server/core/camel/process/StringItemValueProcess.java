package com.zmops.zeus.iot.server.core.camel.process;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.zmops.zeus.iot.server.core.camel.IOTDeviceValue;
import com.zmops.zeus.iot.server.core.worker.data.ItemValue;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author nantian created at 2021/8/23 17:16
 * <p>
 * Json 转 对象，进队列，一定要转成 ItemValue 对象，{@link ItemValue}
 */
public class StringItemValueProcess implements Processor {

    private final Gson gson = new Gson();

    @Override
    public void process(Exchange exchange) throws Exception {
        Message              message   = exchange.getIn();
        List<IOTDeviceValue> valueList = gson.fromJson((String) message.getBody(), new TypeToken<List<IOTDeviceValue>>() {}.getType());

        // 多一步 命名转换，方便 Json 字段理解
        List<ItemValue> itemValueList = new ArrayList<>();
        valueList.forEach(i -> {
            ItemValue item = new ItemValue(i.getDeviceId(), i.getDeviceAttrKey(), i.getDeviceAttrValue());
            if (i.getDeviceTime() != null) {
                item.setClock(i.getDeviceTime());
            }
            itemValueList.add(item);
        });

        exchange.getMessage().setBody(itemValueList);
    }


    private String analysisMessage(InputStream bodyStream) throws IOException {
        ByteArrayOutputStream outStream    = new ByteArrayOutputStream();
        byte[]                contextBytes = new byte[4096];
        int                   realLen;
        while ((realLen = bodyStream.read(contextBytes, 0, 4096)) != -1) {
            outStream.write(contextBytes, 0, realLen);
        }

        // 返回从Stream中读取的字串
        try {
            return outStream.toString("UTF-8");
        } finally {
            outStream.close();
        }
    }
}