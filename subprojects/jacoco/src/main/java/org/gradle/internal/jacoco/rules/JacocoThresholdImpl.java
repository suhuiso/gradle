/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.jacoco.rules;

import org.gradle.testing.jacoco.tasks.rules.JacocoThreshold;
import org.gradle.testing.jacoco.tasks.rules.JacocoThresholdMetric;
import org.gradle.testing.jacoco.tasks.rules.JacocoThresholdValue;

public class JacocoThresholdImpl implements JacocoThreshold {

    private JacocoThresholdMetric metric;
    private JacocoThresholdValue value;
    private Double minimum;
    private Double maximum;

    @Override
    public JacocoThresholdMetric getMetric() {
        return metric;
    }

    @Override
    public void setMetric(JacocoThresholdMetric metric) {
        this.metric = metric;
    }

    @Override
    public JacocoThresholdValue getValue() {
        return value;
    }

    @Override
    public void setValue(JacocoThresholdValue value) {
        this.value = value;
    }

    @Override
    public Double getMinimum() {
        return minimum;
    }

    @Override
    public void setMinimum(Double minimum) {
        this.minimum = minimum;
    }

    @Override
    public Double getMaximum() {
        return maximum;
    }

    @Override
    public void setMaximum(Double maximum) {
        this.maximum = maximum;
    }
}