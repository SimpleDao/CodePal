package com.codepal.ui;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 回合看门狗：检测「模型 / 工具长时间无真实进展」，区分 busy（正常进行）与 stuck（可能卡死 / 断线），
 * 并最终在硬超时触发显式错误（等价 onError），避免轮播提示永远空转而聊天实际已挂。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #start(long, long)} —— 回合开始（每轮模型请求发起）时调用，重置计时；</li>
 *   <li>{@link #pet()} —— 每次真实进展（流式 chunk / 思考 chunk / 工具边界 / 新一轮模型调用）调用，重置 idle；</li>
 *   <li>{@link #pause()} / {@link #resume()} —— 工具执行期间暂停 / 恢复计时（已知在忙，不算卡）；</li>
 *   <li>{@link #stop()} —— 回合结束（onComplete / onError / 用户停止）时调用，幂等。</li>
 * </ul>
 *
 * <p>状态机：IDLE → ACTIVE →(idle≥softMs)→ STUCK →(idle≥hardMs)→ TIMED_OUT → stop。
 * 进入 STUCK 回调 {@code onStuck.accept(true)}（UI 切「已等待 Xs」琥珀态）；
 * 恢复（pet 时从 STUCK 回到 ACTIVE）回调 {@code onStuck.accept(false)}（UI 恢复 busy 态）；
 * 硬超时回调 {@code onTimeout}（UI 停轮播 + 错误气泡 + 复原按钮）。
 */
public class TurnWatchdog {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CP-TurnWatchdog");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<Boolean> onStuck;   // true=进入 stuck（可能卡了），false=恢复 busy
    private final Runnable onTimeout;          // 硬超时：等价 onError，停轮播 + 错误气泡 + 复原发送按钮

    private final AtomicLong lastActivityTs = new AtomicLong(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile boolean paused = false;
    private volatile long softMs;
    private volatile long hardMs;
    private volatile ScheduledFuture<?> tickFuture;

    private enum State { IDLE, ACTIVE, STUCK, TIMED_OUT }

    public TurnWatchdog(Consumer<Boolean> onStuck, Runnable onTimeout) {
        this.onStuck = onStuck;
        this.onTimeout = onTimeout;
    }

    public void start(long softMs, long hardMs) {
        stop();
        this.softMs = softMs;
        this.hardMs = hardMs;
        this.paused = false;
        lastActivityTs.set(System.currentTimeMillis());
        state.set(State.ACTIVE);
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    /** 报告一次真实进展，重置 idle 计时；若处于 STUCK 则恢复 ACTIVE 并通知 UI 恢复 busy 态 */
    public void pet() {
        lastActivityTs.set(System.currentTimeMillis());
        if (state.compareAndSet(State.STUCK, State.ACTIVE)) {
            safeRun(() -> onStuck.accept(false));
        }
    }

    /** 工具执行期间暂停计时（已知在忙，不应判为卡死） */
    public void pause() {
        this.paused = true;
    }

    /** 工具结果返回后恢复计时（pet + 取消暂停） */
    public void resume() {
        this.paused = false;
        pet();
    }

    public void stop() {
        state.set(State.IDLE);
        this.paused = false;
        if (tickFuture != null) {
            try { tickFuture.cancel(false); } catch (Exception ignored) { }
            tickFuture = null;
        }
    }

    public boolean isRunning() {
        return state.get() != State.IDLE;
    }

    private void tick() {
        State s = state.get();
        if (s == State.IDLE || s == State.TIMED_OUT) return;
        if (paused) return; // 工具执行中不计 idle
        long idle = System.currentTimeMillis() - lastActivityTs.get();
        if (s == State.ACTIVE && idle >= softMs) {
            if (state.compareAndSet(State.ACTIVE, State.STUCK)) {
                safeRun(() -> onStuck.accept(true));
            }
        } else if (s == State.STUCK && idle >= hardMs) {
            if (state.compareAndSet(State.STUCK, State.TIMED_OUT)) {
                safeRun(onTimeout);
                stop();
            }
        }
    }

    private void safeRun(Runnable r) {
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) { }
    }
}
