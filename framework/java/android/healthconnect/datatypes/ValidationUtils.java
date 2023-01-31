/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.healthconnect.datatypes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An util class for HC datatype validation
 *
 * @hide
 */
public final class ValidationUtils {
    public static void requireInRange(long value, long lowerBound, long upperBound, String name) {
        if (value < lowerBound) {
            throw new IllegalArgumentException(
                    name + " must not be less than " + lowerBound + ", currently " + value);
        }

        if (value > upperBound) {
            throw new IllegalArgumentException(
                    name + " must not be more than " + upperBound + ", currently " + value);
        }
    }

    public static void requireInRange(
            double value, double lowerBound, double upperBound, String name) {
        if (value < lowerBound) {
            throw new IllegalArgumentException(
                    name + " must not be less than " + lowerBound + ", currently " + value);
        }

        if (value > upperBound) {
            throw new IllegalArgumentException(
                    name + " must not be more than " + upperBound + ", currently " + value);
        }
    }

    public static void validateSampleStartAndEndTime(
            Instant sessionStartTime, Instant sessionEndTime, List<Instant> timeInstants) {
        if (timeInstants.size() > 0) {
            Instant minTime = timeInstants.get(0);
            Instant maxTime = timeInstants.get(0);
            for (Instant instant : timeInstants) {
                if (instant.isBefore(minTime)) {
                    minTime = instant;
                }
                if (instant.isAfter(maxTime)) {
                    maxTime = instant;
                }
            }
            if (minTime.isBefore(sessionStartTime) || maxTime.isAfter(sessionEndTime)) {
                throw new IllegalArgumentException(
                        "Time instant values must be within session interval");
            }
        }
    }

    public static <T extends Comparable<T>> void requireInRangeIfExists(
            Comparable<T> value, T threshold, T limit, String name) {
        if (value != null && value.compareTo(threshold) < 0) {
            throw new IllegalArgumentException(
                    name + " must not be less than " + threshold + ", currently " + value);
        }

        if (value != null && value.compareTo(limit) > 0) {
            throw new IllegalArgumentException(
                    name + " must not be more than " + limit + ", currently " + value);
        }
    }


    public static double valuePerMinute(long value, Instant startTime, Instant endTime) {
        long minutes = Duration.between(startTime, endTime).toMinutes();
        return (double) value / (minutes != 0 ? minutes : 1);
    }

    public static double valuePerSecond(long value, Instant startTime, Instant endTime) {
        long seconds = Duration.between(startTime, endTime).toSeconds();
        return (double) value / (seconds != 0 ? seconds : 1);
    }
}