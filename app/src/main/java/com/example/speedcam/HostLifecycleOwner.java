package com.example.speedcam;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

/**
 * مالك دورة حياة خاص بالكاميرا: بيفضل STARTED طول ما التطبيق شغال، عشان الكاميرا ما تقفلش
 * لما الشاشة تتقفل أو التطبيق يروح للخلفية (وقت ما الخدمة شغالة).
 * مكتوب بالجافا عشان يشتغل مع أي إصدار من مكتبة lifecycle.
 * لازم يتنادى من الخيط الرئيسي.
 */
public final class HostLifecycleOwner implements LifecycleOwner {
    private final LifecycleRegistry registry = new LifecycleRegistry(this);

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return registry;
    }

    public void start() {
        if (registry.getCurrentState() != Lifecycle.State.STARTED) {
            registry.setCurrentState(Lifecycle.State.STARTED);
        }
    }
}
