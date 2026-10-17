/**
 * Copyright (C) 2013- Iordan Iordanov
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */


package com.iiordanov.bVNC.input;
import com.iiordanov.bVNC.RemoteCanvasActivity;
import com.undatech.opaque.Viewable;

import android.os.SystemClock;
import android.view.MotionEvent;

import com.undatech.opaque.InputCarriable;
import com.undatech.opaque.util.GeneralUtils;
import com.undatech.remoteClientUi.R;

public class TouchInputHandlerTouchpad extends TouchInputHandlerGeneric {
    public static final String ID = "TOUCHPAD_MODE";
    static final String TAG = "InputHandlerTouchpad";

    public TouchInputHandlerTouchpad(TouchInputDelegate touchInputDelegate, Viewable viewable,
                                     InputCarriable remoteInput, boolean debugLogging,
                                     float scrollRate) {
        super(touchInputDelegate, viewable, remoteInput, debugLogging, scrollRate);
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return viewable.getResources().getString(R.string.input_method_touchpad_description);
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandler#getId()
     */
    @Override
    public String getId() {
        return ID;
    }

    /*
     * FanRDP touchpad gestures (matching the official Microsoft RDP client):
     *  - Single tap (quickly lift)            = left click.
     *  - Quick double-tap then lift           = double-click (e.g. to open a folder).
     *  - Double-tap then hold (finger stays)  = left-button press and hold (drag).
     *  - Long-press (no prior double-tap)     = right-click.
     *  - Two-finger vertical swipe            = mouse wheel scroll (handled by the base class).
     */
    // How long the second tap may be held before it is treated as a left-button hold
    // instead of a quick double-tap.
    private static final long DOUBLE_CLICK_MAX_HOLD_MS = 250;
    // True while the second tap of a double-tap is on the screen.
    private boolean doubleTapActive = false;
    private long doubleTapDownTime = 0;
    // Fixed position used for the two clicks of a double-click, so the mouse never
    // moves/jitters between them or across consecutive double-clicks.
    private int doubleTapX = 0;
    private int doubleTapY = 0;

    // FanRDP touchpad mouse smoothing:
    // Max per-event finger-move delta (px). Anything larger (e.g. the "MASSIVE delta" Android
    // can deliver when a finger lifts mid-gesture) is clamped so the cursor can't jump across
    // the screen.
    private static final float MAX_EVENT_DELTA = 60f;

    // FanRDP manual double-tap-hold fallback.
    // GestureDetector.onDoubleTap only fires when the two taps land within its double-tap timeout
    // (300ms) and within its slop. When a user double-taps and then HOLDS to drag, the second tap
    // often drifts or comes slightly late, so onDoubleTap never fires and the hold would be misread
    // as a long-press right-click. We therefore track the last quick tap ourselves and, in
    // onLongPress, if a quick tap just preceded the current press, treat it as a LEFT-button drag.
    private static final long DOUBLE_TAP_FALLBACK_MS = 400;   // 1st tap-up -> 2nd tap-down window
    private static final float DOUBLE_TAP_FALLBACK_SLOP = 120f; // how far apart the two taps may be
    private static final long QUICK_TAP_MS = 400;             // down-up duration that counts as a tap
    private static final float QUICK_TAP_MOVE_SLOP = 60f;     // max movement while tapping
    private long lastTapUpTime = 0;
    private int lastTapUpX = 0;
    private int lastTapUpY = 0;
    private long currentDownTime = 0;
    private int currentDownX = 0;
    private int currentDownY = 0;

    // FanRDP: manual take-over of the "double-tap then hold" gesture. We OWN the whole 2nd-tap
    // stream (down/move/up) so the left-button drag can never be missed. The GestureDetector's
    // onDoubleTap/onLongPress are too fragile here (a crisp double-tap is required), so once we
    // detect a 2nd tap we swallow every following event of that interaction ourselves.
    private static final float DRAG_START_SLOP = 20f; // moving the 2nd tap this far = a drag
    private boolean doubleTapArmed = false;           // 2nd tap detected, button already pressed
    private boolean doubleTapMoved = false;           // the 2nd tap has become a drag
    private int doubleTapStartX = 0;                  // touch pos of the 2nd tap down
    private int doubleTapStartY = 0;
    private boolean suppressNextSingleTap = false;    // skip the 1st tap's delayed single-click

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTap (touchpad), e: " + e);

        int metaState = e.getMetaState();

        if (secondPointerWasDown || thirdPointerWasDown) {
            GeneralUtils.debugLog(debugLogging, TAG,
                    "onDoubleTap: multi-finger gesture in progress, not starting left drag");
            return true;
        }

        // Second tap went down: press the left button at a FIXED position. We deliberately do NOT
        // enter drag mode here, so a quick double-tap never moves the mouse even if the finger
        // wiggles. Only if the finger is held long enough does it become a left-button drag.
        doubleTapActive = true;
        doubleTapDownTime = SystemClock.uptimeMillis();
        doubleTapX = getX(e);
        doubleTapY = getY(e);
        remoteInput.getPointer().leftButtonDown(doubleTapX, doubleTapY, metaState);
        viewable.movePanToMakePointerVisible();
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        int action = e.getAction();
        int metaState = e.getMetaState();
        if (doubleTapActive) {
            if (action == MotionEvent.ACTION_UP) {
                // The second tap finished. If it was a quick tap (finger left the screen quickly),
                // complete a double-click at the fixed position (the base handler already released
                // the press from onDoubleTap, so we just add the missing second click). If it was a
                // held tap, it became a left-button drag and the base handler releases the button.
                if (SystemClock.uptimeMillis() - doubleTapDownTime <= DOUBLE_CLICK_MAX_HOLD_MS) {
                    GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTapEvent: quick double-tap -> double-click");
                    remoteInput.getPointer().leftButtonDown(doubleTapX, doubleTapY, metaState);
                    SystemClock.sleep(50);
                    remoteInput.getPointer().releaseButton(doubleTapX, doubleTapY, metaState);
                    viewable.movePanToMakePointerVisible();
                }
                doubleTapActive = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                // If the second finger has been held long enough, this is now a left-button hold/drag:
                // enter drag mode and start dragging from the current position. We reset the drag
                // origin first so the first delta is zero — otherwise getX/getY would subtract the
                // stale dragX from the FIRST tap and jump the cursor far away (it would disappear).
                if (!dragMode && SystemClock.uptimeMillis() - doubleTapDownTime > DOUBLE_CLICK_MAX_HOLD_MS) {
                    GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTapEvent: held second tap -> left drag");
                    dragMode = true;
                    dragX = e.getX();
                    dragY = e.getY();
                    remoteInput.getPointer().moveMouseButtonDown(getX(e), getY(e), metaState);
                    viewable.movePanToMakePointerVisible();
                }
            }
        }
        return super.onDoubleTapEvent(e);
    }

    /*
     * FanRDP: track quick taps and, when the user does a "double-tap then hold", TAKE OVER the
     * whole second-tap gesture ourselves. We press the left button the instant the 2nd tap lands,
     * switch to a left-button drag as soon as it moves, and swallow every event of that interaction
     * so the fragile GestureDetector can't misread it as a right-click or a plain mouse move. This
     * makes the double-tap drag reliable instead of "works only sometimes".
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (e.getPointerCount() == 1) {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) {
                currentDownTime = now;
                currentDownX = Math.round(e.getX());
                currentDownY = Math.round(e.getY());
                // Second tap of a double-tap (a quick tap just happened near here)?
                if (lastTapUpTime != 0
                        && now - lastTapUpTime <= DOUBLE_TAP_FALLBACK_MS
                        && Math.abs(e.getX() - lastTapUpX) <= DOUBLE_TAP_FALLBACK_SLOP
                        && Math.abs(e.getY() - lastTapUpY) <= DOUBLE_TAP_FALLBACK_SLOP) {
                    GeneralUtils.debugLog(debugLogging, TAG,
                            "onTouchEvent: 2nd tap of double-tap -> press left & take over");
                    doubleTapArmed = true;
                    doubleTapMoved = false;
                    doubleTapDownTime = now;
                    doubleTapStartX = Math.round(e.getX());
                    doubleTapStartY = Math.round(e.getY());
                    doubleTapX = remoteInput.getPointer().getX();
                    doubleTapY = remoteInput.getPointer().getY();
                    remoteInput.getPointer().leftButtonDown(doubleTapX, doubleTapY, e.getMetaState());
                    viewable.movePanToMakePointerVisible();
                    suppressNextSingleTap = true; // 1st tap's delayed single-click must be skipped
                    lastTapUpTime = 0;            // consume, so it only counts once
                    return true;                  // swallow this DOWN
                }
                doubleTapArmed = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (doubleTapArmed) {
                    // Held 2nd tap starts moving -> left-button drag.
                    if (!doubleTapMoved
                            && (Math.abs(e.getX() - doubleTapStartX) > DRAG_START_SLOP
                            || Math.abs(e.getY() - doubleTapStartY) > DRAG_START_SLOP)) {
                        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: 2nd tap moved -> left drag");
                        doubleTapMoved = true;
                        dragMode = true;
                        dragX = e.getX();
                        dragY = e.getY();
                        remoteInput.getPointer().moveMouseButtonDown(getX(e), getY(e), e.getMetaState());
                        viewable.movePanToMakePointerVisible();
                    } else if (doubleTapMoved) {
                        // Continue the drag (button already down).
                        viewable.movePanToMakePointerVisible();
                        remoteInput.getPointer().moveMouseButtonDown(getX(e), getY(e), e.getMetaState());
                    }
                    return true; // swallow this MOVE (we own the drag)
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (doubleTapArmed) {
                    if (!doubleTapMoved && now - doubleTapDownTime <= DOUBLE_CLICK_MAX_HOLD_MS) {
                        // Quick 2nd tap, no movement -> double-click: finish 1st click, then 2nd click.
                        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: quick 2nd tap -> double-click");
                        remoteInput.getPointer().releaseButton(doubleTapX, doubleTapY, e.getMetaState());
                        remoteInput.getPointer().leftButtonDown(doubleTapX, doubleTapY, e.getMetaState());
                        SystemClock.sleep(50);
                        remoteInput.getPointer().releaseButton(doubleTapX, doubleTapY, e.getMetaState());
                        viewable.movePanToMakePointerVisible();
                    } else {
                        // Drag (or plain hold) ended -> release the left button.
                        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: drag/hold ended -> release");
                        dragMode = false;
                        remoteInput.getPointer().releaseButton(getX(e), getY(e), e.getMetaState());
                        viewable.movePanToMakePointerVisible();
                    }
                    doubleTapArmed = false;
                    suppressNextSingleTap = false;
                    return true; // swallow this UP
                } else {
                    // A quick press-lift with little movement is a "tap". Keep the last one so a
                    // following press can be recognised as the 2nd tap of a double-tap.
                    if (now - currentDownTime <= QUICK_TAP_MS
                            && Math.abs(e.getX() - currentDownX) <= QUICK_TAP_MOVE_SLOP
                            && Math.abs(e.getY() - currentDownY) <= QUICK_TAP_MOVE_SLOP) {
                        lastTapUpTime = now;
                        lastTapUpX = Math.round(e.getX());
                        lastTapUpY = Math.round(e.getY());
                    }
                }
            }
        }
        return super.onTouchEvent(e);
    }

    /*
     * FanRDP: a single tap whose second tap we already took over fires a delayed single-click
     * (the GestureDetector's double-tap window expired without seeing the 2nd tap). Skip it so a
     * double-tap-hold doesn't issue an unwanted click before the drag.
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (suppressNextSingleTap) {
            suppressNextSingleTap = false;
            GeneralUtils.debugLog(debugLogging, TAG, "onSingleTapConfirmed: suppressed (double-tap take-over)");
            return true;
        }
        return super.onSingleTapConfirmed(e);
    }

    /*
     * FanRDP: touchpad long-press (no prior double-tap) = right mouse button click.
     */
    @Override
    public void onLongPress(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onLongPress (touchpad), e: " + e);

        int metaState = e.getMetaState();

        // Do not send a right-click when the current touch is the held second tap of a
        // double-tap (that is a left-button hold), or when a multi-finger gesture is in progress.
        if (secondPointerWasDown || thirdPointerWasDown || doubleTapActive) {
            GeneralUtils.debugLog(debugLogging, TAG,
                    "onLongPress: double-tap hold or multi-finger gesture in progress, not sending right-click");
            return;
        }

        // FanRDP: a quick tap just before this press means the user is doing "double-tap then hold"
        // but GestureDetector.onDoubleTap did not fire (taps not crisp enough). Turn it into a
        // LEFT-button drag instead of a right-click — this makes the double-tap drag reliable.
        boolean isDoubleTapHold = (lastTapUpTime != 0
                && currentDownTime - lastTapUpTime <= DOUBLE_TAP_FALLBACK_MS
                && Math.abs(currentDownX - lastTapUpX) <= DOUBLE_TAP_FALLBACK_SLOP
                && Math.abs(currentDownY - lastTapUpY) <= DOUBLE_TAP_FALLBACK_SLOP);
        if (isDoubleTapHold) {
            GeneralUtils.debugLog(debugLogging, TAG, "onLongPress: double-tap-hold (fallback) -> left drag");
            lastTapUpTime = 0; // consume the tap so it only counts once
            dragMode = true;
            dragX = e.getX();
            dragY = e.getY();
            remoteInput.getPointer().leftButtonDown(getX(e), getY(e), metaState);
            viewable.movePanToMakePointerVisible();
            return;
        }

        touchInputDelegate.sendShortVibration();
        remoteInput.getPointer().rightButtonDown(getX(e), getY(e), metaState);
        SystemClock.sleep(50);
        remoteInput.getPointer().releaseButton(getX(e), getY(e), metaState);
        viewable.movePanToMakePointerVisible();
    }

    /*
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onScroll(android.view.MotionEvent, android.view.MotionEvent, float, float)
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScroll, e1: " + e1 + ", e2:" + e2);

        // If we are scaling, allow panning around by moving two fingers around the screen
        if (inScaling) {
            float scale = viewable.getZoomFactor();
            touchInputDelegate.showActionBar();
            viewable.relativePan(Math.round(distanceX * scale), Math.round(distanceY * scale));
        } else {
            boolean twoFingers = false;
            int meta = 0;
            if (e1 != null) {
                twoFingers = (e1.getPointerCount() > 1);
            }
            if (e2 != null) {
                twoFingers = twoFingers || (e2.getPointerCount() > 1);
                meta = e2.getMetaState();
            }

            // onScroll called while scaling/swiping gesture is in effect. We ignore the event and pretend it was
            // consumed. This prevents the mouse pointer from flailing around while we are scaling.
            // Also, if one releases one finger slightly earlier than the other when scaling, it causes Android
            // to stick a spiteful onScroll with a MASSIVE delta here.
            // This would cause the mouse pointer to jump to another place suddenly.
            // Hence, we ignore onScroll after scaling until we lift all pointers up.
            if (twoFingers || inSwiping) {
                return true;
            }

            // FanRDP: while the soft keyboard is up, a one-finger swipe pans the desktop view
            // (instead of moving the mouse), so the part of the desktop hidden behind the
            // keyboard can be brought into view.
            if (viewable.getContext() instanceof RemoteCanvasActivity
                    && ((RemoteCanvasActivity) viewable.getContext()).isImeVisible()) {
                float scale = viewable.getZoomFactor();
                viewable.relativePan(Math.round(distanceX * scale), Math.round(distanceY * scale));
                return true;
            }

            // FanRDP: "double-tap then hold and drag". When the user double-taps and the second tap
            // is held, the finger usually starts moving right away to drag. That movement cancels a
            // long-press and, if the taps are not crisp enough for onDoubleTap, becomes this plain
            // mouse-move onScroll. If a quick tap just preceded the current press, treat it as a
            // LEFT-button drag instead of a mouse move — this is what makes the double-tap drag
            // reliable.
            if (lastTapUpTime != 0
                    && e1 != null
                    && e1.getEventTime() - lastTapUpTime <= DOUBLE_TAP_FALLBACK_MS
                    && Math.abs(e1.getX() - lastTapUpX) <= DOUBLE_TAP_FALLBACK_SLOP
                    && Math.abs(e1.getY() - lastTapUpY) <= DOUBLE_TAP_FALLBACK_SLOP) {
                GeneralUtils.debugLog(debugLogging, TAG, "onScroll: double-tap-hold move -> left drag");
                lastTapUpTime = 0; // consume so it only counts once
                dragMode = true;
                dragX = e2.getX();
                dragY = e2.getY();
                remoteInput.getPointer().leftButtonDown(getX(e2), getY(e2), meta);
                viewable.movePanToMakePointerVisible();
                return true;
            }

            touchInputDelegate.showActionBar();

            // If the gesture has just began, then don't allow a big delta to prevent
            // pointer jumps at the start of scrolling.
            if (!inScrolling) {
                inScrolling = true;
                distanceX = getSign(distanceX);
                distanceY = getSign(distanceY);
                distXQueue.clear();
                distYQueue.clear();
            }

            distXQueue.add(distanceX);
            distYQueue.add(distanceY);

            // Only after the first two events have arrived do we start using distanceX and Y
            // In order to effectively discard the last two events (which are typically unreliable
            // because of the finger lifting off).
            if (distXQueue.size() > 2) {
                distanceX = distXQueue.poll();
                distanceY = distYQueue.poll();
            } else {
                return true;
            }

            // Clamp per-event movement so a "MASSIVE delta" (a spurious onScroll that Android
            // delivers when a finger lifts mid-gesture) can't fling the cursor across the screen.
            distanceX = clamp(distanceX, -MAX_EVENT_DELTA, MAX_EVENT_DELTA);
            distanceY = clamp(distanceY, -MAX_EVENT_DELTA, MAX_EVENT_DELTA);

            // Make distanceX/Y display density independent.
            float sensitivity = remoteInput.getPointer().getSensitivity();
            distanceX = sensitivity * distanceX / displayDensity;
            distanceY = sensitivity * distanceY / displayDensity;

            // Compute the absolute new mouse position.
            int newX = Math.round(remoteInput.getPointer().getX() + getDelta(-distanceX));
            int newY = Math.round(remoteInput.getPointer().getY() + getDelta(-distanceY));

            remoteInput.getPointer().moveMouse(newX, newY, meta);
        }
        viewable.movePanToMakePointerVisible();
        return true;
    }

    /*
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onDown(android.view.MotionEvent)
     */
    @Override
    public boolean onDown(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onDown, e: " + e);
        panRepeater.stop();
        return true;
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandlerGeneric#getX(android.view.MotionEvent)
     */
    protected int getX(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceX = e.getX() - dragX;
            dragX = e.getX();
            // Compute the absolute new X coordinate.
            return Math.round(remoteInput.getPointer().getX() + getDelta(distanceX));
        }
        dragX = e.getX();
        return remoteInput.getPointer().getX();
    }

    /*
     * (non-Javadoc)
     * @see com.iiordanov.bVNC.input.InputHandlerGeneric#getY(android.view.MotionEvent)
     */
    protected int getY(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceY = e.getY() - dragY;
            dragY = e.getY();
            // Compute the absolute new Y coordinate.
            return Math.round(remoteInput.getPointer().getY() + getDelta(distanceY));
        }
        dragY = e.getY();
        return remoteInput.getPointer().getY();
    }

    /**
     * Computes how far the pointer will move.
     * @param distance
     * @return
     */
    private float getDelta(float distance) {
        float delta = (float) (distance * Math.cbrt(viewable.getZoomFactor()));
        return computeAcceleration(delta);
    }

    /**
     * Clamps a value into [min, max].
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Computes the acceleration depending on the size of the supplied delta.
     * @param delta
     * @return
     */
    private float computeAcceleration(float delta) {
        // FanRDP: the touchpad cursor now follows the finger 1:1 (Microsoft RDP style). The
        // original acceleration amplified fast moves up to 4.5x, which made the cursor outrun
        // the finger, jump off the visible area and "disappear". We keep only a small dead-zone
        // for micro-movements (to damp jitter); everything else moves exactly with the finger.
        float origSign = getSign(delta);
        delta = Math.abs(delta);
        if (delta <= 8) {
            delta = delta * 0.5f;
        }
        return origSign * delta;
    }
}
