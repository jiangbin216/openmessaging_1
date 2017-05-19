package io.openmessaging.demo;

import io.openmessaging.KeyValue;
import io.openmessaging.Message;
import io.openmessaging.MessageHeader;
import io.openmessaging.PullConsumer;

import java.nio.MappedByteBuffer;
import java.util.*;

/**
 * Created by lee on 5/16/17.
 */
public class DefaultPullConsumer implements PullConsumer{
    private KeyValue properties;
    private String queue;
    private List<String> bucketList = new ArrayList<>();
    private int curBucket = 0;

    private Map<String, MessageFile> messageFileMap = null;
    private Map<String, Bookkeeper> consumeRecord = null;


    public DefaultPullConsumer(KeyValue properties) {
        this.properties = properties;

    }

    @Override public KeyValue properties() { return properties; }

    @Override public  Message poll() {
        Message message = null;
        while (curBucket  < bucketList.size()) {
            String bucket = bucketList.get(curBucket);
            message = pullMessage(bucket);
            if (message != null) {
                return message;
            }
            curBucket++;
        }

        return null;
    }

    public  Message pullMessage(String bucket) {
        Message message = null;

        if (messageFileMap.get(bucket) == null)
            messageFileMap.put(bucket, new MessageFile(properties, bucket)); // 这里隐含文件操作
        if (consumeRecord.get(bucket) == null)
            consumeRecord.put(bucket, new Bookkeeper());

        MessageFile msgFile = messageFileMap.get(bucket);
        Bookkeeper bookkeeper = consumeRecord.get(bucket);

        int curBufIndex = bookkeeper.getCurBufIndex();
        if (curBufIndex >= msgFile.getMapBufList().size())
            return message; // 当前bucket对应的文件已经被消费完

        MappedByteBuffer mapBuf = msgFile.getMapBufList().get(curBufIndex);
        int curOffset = bookkeeper.getCurOffset();

        // 取消息
        int i = curOffset;
        byte[] msgBytes = null;

        for (; mapBuf.get(i) != 0 && mapBuf.get(i) != '\n'; i++);

        if (mapBuf.get(i) == 0) return null;    // 到达边界

        // 不跨越buffer
        if (i < mapBuf.capacity()) {    // i 此时指向 '\n'
            msgBytes = new byte[i - curOffset];
            int k = 0;

            for (int j = curOffset; j < i; j++)
                msgBytes[k++] = mapBuf.get();

            mapBuf.get();   //让 buffer的position指针跳过'\n'

            if (i+1 == mapBuf.capacity()) { // 已到达当前buffer的末尾
                bookkeeper.increaseBufIndex();
                bookkeeper.setOffset(0);
            } else    bookkeeper.setOffset(i+1);   // 更新在buffer内的偏移量
        }

        // 跨越不同的buffer, 进入下一个buffer
        else {
            bookkeeper.increaseBufIndex();
            bookkeeper.setOffset(0);
            int otherBufIndex = bookkeeper.getCurBufIndex();
            int otherOffset = bookkeeper.getCurOffset();
            MappedByteBuffer otherMapBuf = msgFile.getMapBufList().get(otherBufIndex);

            int w = otherOffset;
            for (; otherMapBuf.get(w) != 0 && otherMapBuf.get(w) != '\n'; w++);

            if (otherMapBuf.get(w) == 0)    return null;    //到达边界

            int firstLen = mapBuf.capacity() - curOffset;
            int secondLen = w - otherOffset;

            msgBytes = new byte[firstLen + secondLen];

            // 获取前半段
            int k = 0;
            for (int j = curOffset; j < mapBuf.capacity(); j++)
                msgBytes[k++] = mapBuf.get();
            // 获取后半段
            for (int j = otherOffset; j < w; j++)
                msgBytes[k++] = otherMapBuf.get();

            otherMapBuf.get();  // 让position指针跳过'\n'

            // 更新buffer内偏移量
            bookkeeper.setOffset(w+1);
        }
        return assemble(msgBytes);
    }

    public Message assemble(byte[] msgBytes) {
        DefaultMessageFactory messageFactory = new DefaultMessageFactory();
        Message message = null;
        int i, j;
        // 获取property
        DefaultKeyValue property = new DefaultKeyValue();
        for (i = 0; i < msgBytes.length && msgBytes[i] != ','; i++);
        byte[] propertyBytes = Arrays.copyOfRange(msgBytes, 0, i);  // [start, end)
        insertKVs(propertyBytes, property);
        j = ++i; // 跳过","

        // 获取headers
        DefaultKeyValue header = new DefaultKeyValue();
        for (; i < msgBytes.length && msgBytes[i] != ','; i++);
        byte[] headerBytes = Arrays.copyOfRange(msgBytes, j, i);
        insertKVs(headerBytes, header);
        j = ++i; // 跳过","

        // 获取body
        for (; i < msgBytes.length && msgBytes[i] != '\n'; i++);
        byte[] body = Arrays.copyOfRange(msgBytes, j, i);

        // 组装
        String queueOrTopic = header.getString(MessageHeader.TOPIC);
        if (queueOrTopic != null)
            message = messageFactory.createBytesMessageToTopic(queueOrTopic, body);
        else
            message = messageFactory.createBytesMessageToQueue(queueOrTopic, body);

        // put property
        for (String key: property.keySet()) {
            if (header.isInt(key))
                message.putProperties(key, property.getInt(key));
            else if (header.isDouble(key))
                message.putProperties(key, property.getDouble(key));
            else if (header.isLong(key))
                message.putProperties(key, property.getLong(key));
            else
                message.putProperties(key, property.getString(key));
        }

        // put headers
        for (String key: header.keySet()) {
            if (header.isInt(key))
                message.putHeaders(key, header.getInt(key));
            else if (header.isDouble(key))
                message.putHeaders(key, header.getDouble(key));
            else if (header.isLong(key))
                message.putHeaders(key, header.getLong(key));
            else
                message.putHeaders(key, header.getString(key));
        }

        return message;

    }

    public void insertKVs(byte[] kvBytes, KeyValue map) {
        String kvStr = new String(kvBytes);
        String[] kvPairs = kvStr.split("\\|");
        for (String kv: kvPairs) {
            String[] tuple = kv.split("#");
            if(tuple[1].startsWith("i"))
                map.put(tuple[0], Integer.parseInt(tuple[1].substring(1)));
            else if(tuple[1].startsWith("d"))
                map.put(tuple[0], Double.parseDouble(tuple[1].substring(1)));
            else if (tuple[1].startsWith("l"))
                map.put(tuple[0], Long.parseLong(tuple[1].substring(1)));
            else
                map.put(tuple[0], tuple[1].substring(1));
        }



    }

    @Override public Message poll(KeyValue properties) { throw new UnsupportedOperationException("Unsupported"); }

    @Override public synchronized void attachQueue(String queueName, Collection<String> topics) {
        if (queue != null && !queue.equals(queueName))
            throw new ClientOMSException("You have already attached to a queue: " + queue);
        queue = queueName;

        bucketList.addAll(topics);
        bucketList.add(queueName);

        // 初始化
        messageFileMap = new HashMap<>(bucketList.size());
        consumeRecord = new HashMap<>(bucketList.size());


    }

    @Override public void ack(String messageId) { throw new UnsupportedOperationException("Unsupported"); }

    @Override public void ack(String messageId, KeyValue properties) {
        throw new UnsupportedOperationException("Unsupported");
    }

}