package com.uber.rss.messages;

import io.netty.buffer.ByteBuf;

/***
 * Message sent by upload client to indicate the uploading is finished.
 * TODO remove old FinishUploadRequest/Response later
 */
public class FinishUpload2Message extends BaseMessage {

    public static final byte ACK_FLAG_NO_ACK = 0;
    public static final byte ACK_FLAG_HAS_ACK = 1;

    private long taskAttemptId;
    private long timestamp;
    private byte ackFlag;

    public FinishUpload2Message(long taskAttemptId, long timestamp, byte ackFlag) {
        this.taskAttemptId = taskAttemptId;
        this.timestamp = timestamp;
        this.ackFlag = ackFlag;
    }

    @Override
    public int getMessageType() {
        return MessageConstants.MESSAGE_FinishUpload2Message;
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeLong(taskAttemptId);
        buf.writeLong(timestamp);
        buf.writeByte(ackFlag);
    }

    public static FinishUpload2Message deserialize(ByteBuf buf) {
        long taskAttemptId = buf.readLong();
        long timestamp = buf.readLong();
        byte ackFlag = buf.readByte();
        return new FinishUpload2Message(taskAttemptId, timestamp, ackFlag);
    }

    public long getTaskAttemptId() {
        return taskAttemptId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte getAckFlag() {
        return ackFlag;
    }

    @Override
    public String toString() {
        return "FinishUpload2Message{" +
                "taskAttemptId=" + taskAttemptId +
                ",timestamp=" + timestamp +
                ",ackFlag=" + ackFlag +
                '}';
    }
}