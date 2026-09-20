package com.byd.dashcast.privileged;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 触摸/按键注入。只有 uid 2000 的进程能用 —— InputManager.injectInputEvent 需要
 * INJECT_EVENTS（signature 级），普通 App 申请不到。
 *
 * 走这条路的意义：一次调用就是一次 Binder 事务，微秒~亚毫秒级；而 shell 的 `input` 命令
 * 每个事件都要 fork 一个 ART 虚拟机，本车实测 40~140 ms/次，一次拖拽上百个事件就废了。
 *
 * 事件时间戳用 SystemClock.uptimeMillis() 基准。App 侧直接把它收到的 MotionEvent
 * 的 getDownTime()/getEventTime() 原样传过来即可（同一台设备，基准一致）。
 */
final class InputInjector {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC —— 不等待分发完成，延迟最低。 */
    private static final int INJECT_MODE_ASYNC = 0;

    private final Object manager;
    private final Method injectInputEvent;
    private final Method setDisplayId;
    private final int displayId;

    private InputInjector(Object manager, Method injectInputEvent,
                          Method setDisplayId, int displayId) {
        this.manager = manager;
        this.injectInputEvent = injectInputEvent;
        this.setDisplayId = setDisplayId;
        this.displayId = displayId;
    }

    static InputInjector create(int displayId) throws Exception {
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        Method getInstance = im.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object mgr = getInstance.invoke(null);
        if (mgr == null) {
            throw new IllegalStateException("InputManager.getInstance() 返回 null");
        }
        Method inject = im.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        // setDisplayId 定义在 @hide 的 InputEvent 基类上，MotionEvent/KeyEvent 都能用。
        Method setDisp = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
        setDisp.setAccessible(true);
        return new InputInjector(mgr, inject, setDisp, displayId);
    }

    int displayId() {
        return displayId;
    }

    /**
     * @param action    取 PrivilegedProtocol.TOUCH_*，与 MotionEvent.ACTION_* 数值一致
     * @param downTime  这一轮手势的按下时刻（uptimeMillis）
     * @param eventTime 本事件的发生时刻（uptimeMillis）
     */
    boolean touch(int action, float x, float y, long downTime, long eventTime) {
        MotionEvent e = null;
        try {
            e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayId.invoke(e, displayId);
            Object r = injectInputEvent.invoke(manager, e, INJECT_MODE_ASYNC);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        } finally {
            if (e != null) {
                try {
                    e.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    boolean key(int keyCode, int action) {
        try {
            long now = SystemClock.uptimeMillis();
            // KeyEvent 不需要 recycle：它不是从对象池 obtain 出来的，直接 new。
            KeyEvent e = new KeyEvent(now, now, action, keyCode, 0);
            setDisplayId.invoke(e, displayId);
            Object r = injectInputEvent.invoke(manager, e, INJECT_MODE_ASYNC);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }
}
