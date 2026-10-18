package com.example;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;

import com.example.config.LayoutPresetManager;

/**
 * Attaches drag-to-move, pinch-to-scale, and boundary constraints to an overlay view.
 * Respects element lock flags and notifies layout persistence.
 */
public class TouchOverlayHelper implements View.OnTouchListener {

    public interface OnOverlayModifiedListener {
        void onOverlayMovedOrResized(float xPercent, float yPercent, float widthPercent, float heightPercent);
    }

    private final View mTargetView;
    private final View mParentContainer;
    private final LayoutPresetManager.LayoutElement mElement;
    private final OnOverlayModifiedListener mListener;

    private float mInitialX;
    private float mInitialY;
    private float mTouchDownX;
    private float mTouchDownY;

    private final ScaleGestureDetector mScaleDetector;
    private boolean mIsScaling = false;

    public TouchOverlayHelper(final View targetView, final View parentContainer,
                              LayoutPresetManager.LayoutElement element,
                              OnOverlayModifiedListener listener) {
        mTargetView = targetView;
        mParentContainer = parentContainer;
        mElement = element;
        mListener = listener;

        mScaleDetector = new ScaleGestureDetector(targetView.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mElement.isLocked) {
                    return false;
                }
                mIsScaling = true;
                float scaleFactor = detector.getScaleFactor();
                int currentW = mTargetView.getWidth();
                int currentH = mTargetView.getHeight();

                int newW = (int) (currentW * scaleFactor);
                int newH = (int) (currentH * scaleFactor);

                int parentW = mParentContainer.getWidth();
                int parentH = mParentContainer.getHeight();

                int minW = parentW / 5;
                int maxW = parentW;
                int minH = parentH / 5;
                int maxH = parentH;

                newW = Math.max(minW, Math.min(newW, maxW));
                newH = Math.max(minH, Math.min(newH, maxH));

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mTargetView.getLayoutParams();
                lp.width = newW;
                lp.height = newH;
                mTargetView.setLayoutParams(lp);

                clampPosition();
                notifyModified();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mIsScaling = false;
            }
        });

        mTargetView.setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        mScaleDetector.onTouchEvent(event);

        if (mElement.isLocked) {
            return false;
        }

        if (mIsScaling || event.getPointerCount() > 1) {
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mInitialX = mTargetView.getX();
                mInitialY = mTargetView.getY();
                mTouchDownX = event.getRawX();
                mTouchDownY = event.getRawY();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - mTouchDownX;
                float dy = event.getRawY() - mTouchDownY;

                mTargetView.setX(mInitialX + dx);
                mTargetView.setY(mInitialY + dy);

                clampPosition();
                notifyModified();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                clampPosition();
                notifyModified();
                return true;
        }
        return false;
    }

    private void clampPosition() {
        int parentW = mParentContainer.getWidth();
        int parentH = mParentContainer.getHeight();
        if (parentW == 0 || parentH == 0) return;

        float x = mTargetView.getX();
        float y = mTargetView.getY();
        int w = mTargetView.getWidth();
        int h = mTargetView.getHeight();

        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + w > parentW) x = parentW - w;
        if (y + h > parentH) y = parentH - h;

        mTargetView.setX(x);
        mTargetView.setY(y);
    }

    private void notifyModified() {
        int parentW = mParentContainer.getWidth();
        int parentH = mParentContainer.getHeight();
        if (parentW == 0 || parentH == 0) return;

        float xP = mTargetView.getX() / parentW;
        float yP = mTargetView.getY() / parentH;
        float wP = (float) mTargetView.getWidth() / parentW;
        float hP = (float) mTargetView.getHeight() / parentH;

        mElement.xPercent = xP;
        mElement.yPercent = yP;
        mElement.widthPercent = wP;
        mElement.heightPercent = hP;

        if (mListener != null) {
            mListener.onOverlayMovedOrResized(xP, yP, wP, hP);
        }
    }
}
