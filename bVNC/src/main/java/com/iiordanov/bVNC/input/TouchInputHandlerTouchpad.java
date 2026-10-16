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
                // enter drag mode and start dragging from the current position.
                if (!dragMode && SystemClock.uptimeMillis() - doubleTapDownTime > DOUBLE_CLICK_MAX_HOLD_MS) {
                    GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTapEvent: held second tap -> left drag");
                    dragMode = true;
                    remoteInput.getPointer().moveMouseButtonDown(getX(e), getY(e), metaState);
                    viewable.movePanToMakePointerVisible();
                }
            }
        }
        return super.onDoubleTapEvent(e);
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
        float origSign = getSign(delta);
        delta = Math.abs(delta);
        boolean accelerated = remoteInput.getPointer().isAccelerated();
        if (delta <= 15) {
            delta = delta * 0.75f;
        } else if (accelerated && delta <= 70.0f) {
            delta = delta * delta / 20.0f;
        } else if (accelerated) {
            // FanRDP: smooth, continuous extension beyond 70 instead of the original x4.5,
            // which made the cursor "fly away" on fast moves.
            delta = 70.0f * 70.0f / 20.0f + (delta - 70.0f) * 1.5f;
        }
        return origSign * delta;
    }
}
