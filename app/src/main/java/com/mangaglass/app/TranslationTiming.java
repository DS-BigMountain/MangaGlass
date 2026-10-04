package com.mangaglass.app;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** A sequential, monotonic trace. No screenshot, text, model or error can enter a record. */
final class TranslationTiming {
    enum Stage {
        CAPTURE_WAIT("capture_wait_ms", "截屏等待"),
        CAPTURE_PROCESS("capture_process_ms", "截屏像素处理"),
        WORKER_WAIT("worker_wait_ms", "本机任务等待"),
        IMAGE_PREPARE("image_prepare_ms", "图片编码与请求准备"),
        AI_REQUEST("ai_request_ms", "AI 请求往返"),
        RESPONSE_PROCESS("response_process_ms", "响应解析与坐标处理"),
        UI_WAIT("ui_wait_ms", "界面调度等待"),
        LAYOUT("layout_ms", "译文测量与排版"),
        FRAME_WAIT("frame_wait_ms", "覆盖首帧等待"),
        DRAW("draw_ms", "覆盖绘制");

        final String column, title;
        Stage(String column, String title) { this.column=column; this.title=title; }
    }

    /** These are subdivisions of AI_REQUEST, not additional time in the overall total. */
    enum NetworkPhase {
        CONNECT("network_connect_ms", "连接准备（含 DNS / TLS）"),
        UPLOAD("network_upload_ms", "发送请求与图片"),
        WAIT("network_wait_ms", "等待首段响应"),
        RECEIVE("network_receive_ms", "接收剩余响应");
        final String column,title;
        NetworkPhase(String column,String title) { this.column=column; this.title=title; }
    }

    static final class Record {
        final long startedAtMillis, totalMillis, firstFrameMillis;
        private final long[] durations;
        private final long[] network;
        Record(long startedAtMillis, long totalMillis, long firstFrameMillis, long[] durations) {
            this(startedAtMillis,totalMillis,firstFrameMillis,durations,unmeasuredNetwork());
        }
        Record(long startedAtMillis,long totalMillis,long firstFrameMillis,long[] durations,long[] network) {
            if(durations.length!=Stage.values().length) throw new IllegalArgumentException("Invalid timing stages");
            if(network.length!=NetworkPhase.values().length) throw new IllegalArgumentException("Invalid network phases");
            this.startedAtMillis=startedAtMillis; this.totalMillis=totalMillis;
            this.firstFrameMillis=firstFrameMillis; this.durations=durations.clone();
            this.network=network.clone();
        }
        long duration(Stage stage) { return durations[stage.ordinal()]; }
        long duration(NetworkPhase phase) { return network[phase.ordinal()]; }
        boolean hasNetworkDetails() { return network[NetworkPhase.CONNECT.ordinal()]>=0; }
        boolean completed() { return firstFrameMillis>=0; }
        long localMillis() { return totalMillis-Math.max(0,duration(Stage.AI_REQUEST)); }
    }

    private final LongSupplier clock;
    private final long startedAtMillis, started;
    private final long[] durations=new long[Stage.values().length];
    private final long[] network=unmeasuredNetwork();
    private Stage stage=Stage.CAPTURE_WAIT;
    private NetworkPhase networkPhase;
    private long boundary;
    private long networkBoundary;
    private boolean finished;

    TranslationTiming() { this(System.currentTimeMillis(),System.nanoTime()/1_000_000,() -> System.nanoTime()/1_000_000); }
    TranslationTiming(long wallTime, long monotonicStart, LongSupplier clock) {
        startedAtMillis=wallTime; started=boundary=monotonicStart; this.clock=clock;
        Arrays.fill(durations,-1); durations[stage.ordinal()]=0;
    }
    synchronized void enter(Stage next) {
        if(finished || next==stage) return;
        long now=Math.max(boundary,clock.getAsLong());
        closeNetwork(now);
        durations[stage.ordinal()]+=now-boundary; boundary=now; stage=next;
        durations[stage.ordinal()]=Math.max(0,durations[stage.ordinal()]);
        if(next==Stage.AI_REQUEST) {
            networkPhase=NetworkPhase.CONNECT; networkBoundary=now;
            network[networkPhase.ordinal()]=Math.max(0,network[networkPhase.ordinal()]);
        }
    }
    synchronized void network(NetworkPhase next) {
        if(finished || stage!=Stage.AI_REQUEST || networkPhase==null || next==networkPhase) return;
        long now=Math.max(networkBoundary,clock.getAsLong());
        closeNetwork(now); networkPhase=next; networkBoundary=now;
        network[next.ordinal()]=Math.max(0,network[next.ordinal()]);
    }
    private void closeNetwork(long now) {
        if(networkPhase!=null) network[networkPhase.ordinal()]+=Math.max(0,now-networkBoundary);
        networkPhase=null;
    }
    private static long[] unmeasuredNetwork() {
        long[] values=new long[NetworkPhase.values().length]; Arrays.fill(values,-1); return values;
    }
    /** Exactly one caller gets a record; late worker callbacks after cancellation do nothing. */
    synchronized Record finish(boolean frameDrawn) {
        if(finished) return null;
        long now=Math.max(boundary,clock.getAsLong());
        closeNetwork(now);
        durations[stage.ordinal()]+=now-boundary; finished=true;
        return new Record(startedAtMillis,now-started,frameDrawn ? now-started : -1,durations,network);
    }
}
