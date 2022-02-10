package com.airbnb.lottie.model.layer;

import com.airbnb.lottie.L;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.animation.keyframe.BaseKeyframeAnimation;
import com.airbnb.lottie.animation.keyframe.ValueCallbackKeyframeAnimation;
import com.airbnb.lottie.model.animatable.AnimatableFloatValue;
import com.airbnb.lottie.utils.Utils;
import com.airbnb.lottie.value.LottieValueCallback;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieDrawable;

import ohos.agp.render.Canvas;
import ohos.agp.render.Paint;
import ohos.agp.utils.Matrix;
import ohos.agp.utils.RectFloat;
import ohos.hiviewdfx.HiTraceId;
import ohos.utils.LongPlainArray;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CompositionLayer extends BaseLayer {
    @Nullable private BaseKeyframeAnimation<Float, Float> timeRemapping;
    private final List<BaseLayer> layers = new ArrayList<>();
    private final RectFloat rectf = new RectFloat();
    private final RectFloat newClipRect = new RectFloat();
    private Paint layerPaint = new Paint();
    @Nullable
    private Boolean hasMatte;
    @Nullable private Boolean hasMasks;

    public CompositionLayer(LottieDrawable lottieDrawable, Layer layerModel, List<Layer> layerModels,
        LottieComposition composition) {
        super(lottieDrawable, layerModel);
        AnimatableFloatValue timeRemapping = layerModel.getTimeRemapping();
        if (timeRemapping != null) {
            this.timeRemapping = timeRemapping.createAnimation();
            addAnimation(this.timeRemapping);
            this.timeRemapping.addUpdateListener(this);
        } else {
            this.timeRemapping = null;
        }

        LongPlainArray<BaseLayer> layerMap = new LongPlainArray<>(
            composition.getLayers().size());

        BaseLayer mattedLayer = null;
        for (int i = layerModels.size() - 1; i >= 0; i--) {
            Layer lm = layerModels.get(i);
            BaseLayer layer = forModel(lm, lottieDrawable, composition);
            if (layer == null) {
                continue;
            }
            layerMap.put(layer.getLayerModel().getId(), layer);
            if (mattedLayer != null) {
                mattedLayer.setMatteLayer(layer);
                mattedLayer = null;
            } else {
                layers.add(0, layer);
                switch (lm.getMatteType()) {
                    case ADD:
                    case INVERT:
                        mattedLayer = layer;
                        break;
                }
            }
        }

        for (int i = 0; i < layerMap.size(); i++) {
            long key = layerMap.keyAt(i);
            Optional<BaseLayer> layerView = layerMap.get(key);
            // This shouldn't happen but it appears as if sometimes on pre-lollipop devices when
            // compiled with d8, layerView is null sometimes.
            if (!layerView.isPresent()) {
                continue;
            }
            Optional<BaseLayer> parentLayer = layerMap.get(layerView.get().getLayerModel().getParentId());
            if (parentLayer.isPresent()) {
                layerView.get().setParentLayer(parentLayer.get());
            }
        }
    }
    @Override public void setOutlineMasksAndMattes(boolean outline) {
        super.setOutlineMasksAndMattes(outline);
        for (BaseLayer layer : layers) {
            layer.setOutlineMasksAndMattes(outline);
        }
    }

    @Override
    void drawLayer(Canvas canvas, Matrix parentMatrix, float parentAlpha) {
        HiTraceId traceid = L.beginSection("CompositionLayer#draw");
        newClipRect.modify(0, 0, layerModel.getPreCompWidth(), layerModel.getPreCompHeight());
        parentMatrix.mapRect(newClipRect);

        // Apply off-screen rendering only when needed in order to improve rendering performance.
        boolean isDrawingWithOffScreen = lottieDrawable.isApplyingOpacityToLayersEnabled() && layers.size() > 1
            && parentAlpha != 255;
        if (isDrawingWithOffScreen) {
            layerPaint.setAlpha(parentAlpha/255.0f);
            Utils.saveLayerCompat(canvas, newClipRect, layerPaint);
        } else {
            canvas.save();
        }

        float childAlpha = isDrawingWithOffScreen ? 255 : parentAlpha;
        for (int i = layers.size() - 1; i >= 0; i--) {

            if (!newClipRect.isEmpty()) {
                canvas.clipRect(newClipRect);
            }

            BaseLayer layer = layers.get(i);
            layer.draw(canvas, parentMatrix, childAlpha);
    	}
        canvas.restore();
        L.endSection(traceid);
    }

    @Override
    public void getBounds(RectFloat outBounds, Matrix parentMatrix, boolean applyParents) {
        super.getBounds(outBounds, parentMatrix, applyParents);
        for (int i = layers.size() - 1; i >= 0; i--) {
            rectf.modify(0, 0, 0, 0);
            layers.get(i).getBounds(rectf, boundsMatrix, true);
            outBounds.fuse(rectf);
        }
    }

    //FloatRange(from = 0f, to = 1f)
    @Override
    public void setProgress(float progress) {
        super.setProgress(progress);
        if (timeRemapping != null) {
            float durationFrames = lottieDrawable.getComposition().getDurationFrames() + 0.01f;
            float compositionDelayFrames = layerModel.getComposition().getStartFrame();
            float remappedFrames = timeRemapping.getValue() * layerModel.getComposition().getFrameRate()
                - compositionDelayFrames;
            progress = remappedFrames / durationFrames;
        }
        if (timeRemapping == null) {
            progress -= layerModel.getStartProgress();
        }
        if (layerModel.getTimeStretch() != 0) {
            progress /= layerModel.getTimeStretch();
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            layers.get(i).setProgress(progress);
        }
    }

    public boolean hasMasks() {
        if (hasMasks == null) {
            for (int i = layers.size() - 1; i >= 0; i--) {
                BaseLayer layer = layers.get(i);
                if (layer instanceof ShapeLayer) {
                    if (layer.hasMasksOnThisLayer()) {
                        hasMasks = true;
                        return true;
                    }
                } else if (layer instanceof CompositionLayer && ((CompositionLayer) layer).hasMasks()) {
                    hasMasks = true;
                    return true;
                }
            }
            hasMasks = false;
        }
        return hasMasks;
    }

    public boolean hasMatte() {
        if (hasMatte == null) {
            if (hasMatteOnThisLayer()) {
                hasMatte = true;
                return true;
            }

            for (int i = layers.size() - 1; i >= 0; i--) {
                if (layers.get(i).hasMatteOnThisLayer()) {
                    hasMatte = true;
                    return true;
                }
            }
            hasMatte = false;
        }
        return hasMatte;
    }

    @Override
    protected void resolveChildKeyPath(KeyPath keyPath, int depth, List<KeyPath> accumulator,
                                       KeyPath currentPartialKeyPath) {
		    for (int i = 0; i < layers.size(); i++) {
		      layers.get(i).resolveKeyPath(keyPath, depth, accumulator, currentPartialKeyPath);
        }
    }

    @Override
    public <T> void addValueCallback(T property, @Nullable LottieValueCallback<T> callback) {
        super.addValueCallback(property, callback);

        if (property == LottieProperty.TIME_REMAP) {
            if (callback == null) {
                if (timeRemapping != null) {
                    timeRemapping.setValueCallback(null);
                }
            } else {
                timeRemapping = new ValueCallbackKeyframeAnimation<>((LottieValueCallback<Float>) callback);
                timeRemapping.addUpdateListener(this);
                addAnimation(timeRemapping);
            }
        }
    }
}
