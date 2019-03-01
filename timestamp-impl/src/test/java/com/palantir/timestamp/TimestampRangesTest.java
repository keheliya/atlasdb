/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;

import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class TimestampRangesTest {
    private static final long SEVENTY_THREE = 73L;
    private static final long EIGHTY_TWO = 82L;

    private static final TimestampRange SEVENTY_THREE_TO_EIGHTY_TWO
            = TimestampRange.createInclusiveRange(SEVENTY_THREE, EIGHTY_TWO);

    @DataPoints
    public static SearchEnd[] searchEnd = SearchEnd.values();

    //these tests should hold for both highest value and lowest value search
    @Theory
    public void canGetTimestampFromRangeIfItIsTheLowerBound(SearchEnd searchEnd) {
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 3, 10, searchEnd))
                .isPresent()
                .hasValue(SEVENTY_THREE);
    }

    @Theory
    public void canGetTimestampFromRangeIfItIsTheUpperBound(SearchEnd searchEnd) {
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 2, 10, searchEnd))
                .isPresent()
                .hasValue(EIGHTY_TWO);
    }

    @Theory
    public void canGetTimestampsFromRangeInTheMiddle(SearchEnd searchEnd) {
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 7, 10, searchEnd))
                .isPresent()
                .hasValue(77L);
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 8, 10, searchEnd))
                .isPresent()
                .hasValue(78L);
    }

    @Theory
    public void canHandleMultipleValidMatches(SearchEnd searchEnd) {
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 1, 2, searchEnd))
                .isPresent()
                .satisfies(optionalLong -> {
                    long value = optionalLong.getAsLong();
                    assertThat(value).isIn(73L, 75L, 77L, 79L, 81L);
                });
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 0, 2, searchEnd))
                .isPresent()
                .satisfies(optionalLong -> {
                    long value = optionalLong.getAsLong();
                    assertThat(value).isIn(74L, 76L, 78L, 80L, 82L);
                });
    }

    @Theory
    public void canHandleNegativeResidues(SearchEnd searchEnd) {
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, -7, 10, searchEnd))
                .isPresent()
                .hasValue(SEVENTY_THREE);
        assertThat(getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, -5, 10, searchEnd))
                .isPresent()
                .hasValue(75);
    }

    @Theory
    public void throwsIfModulusIsNegative(SearchEnd searchEnd) {
        assertThatThrownBy(() -> getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 3, -8, searchEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Modulus should be positive, but found -8.");
        assertThatThrownBy(() -> getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 4, -2, searchEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Modulus should be positive, but found -2.");
    }

    @Theory
    public void throwsIfModulusIsZero(SearchEnd searchEnd) {
        assertThatThrownBy(() -> getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 0, 0, searchEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Modulus should be positive, but found 0.");
    }

    @Theory
    public void throwsIfResidueEqualsOrExceedsModulus(SearchEnd searchEnd) {
        assertThatThrownBy(() -> getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 2, 2, searchEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Absolute value of residue 2 equals or exceeds modulus 2 - no solutions");
        assertThatThrownBy(() -> getTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, -3, 2, searchEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("Absolute value of residue -3 equals or exceeds modulus 2 - no solutions");
    }

    @Theory
    public void returnsAbsentIfTimestampRangeDoesNotContainAnyValuesMatchingModulus(SearchEnd searchEnd) {
        TimestampRange oneTimestamp = TimestampRange.createInclusiveRange(77, 77);
        assertThat(getTimestampMatchingModulus(oneTimestamp, 6, 10, searchEnd)).isNotPresent();
    }

    @Test
    public void lowestSearchShouldReturnLowest() {
        assertThat(TimestampRanges.getLowestTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 1, 2))
                .isPresent()
                .hasValue(SEVENTY_THREE);
    }

    @Test
    public void highestSearchShouldReturnHighest() {
        assertThat(TimestampRanges.getHighestTimestampMatchingModulus(SEVENTY_THREE_TO_EIGHTY_TWO, 0, 2))
                .isPresent()
                .hasValue(EIGHTY_TWO);
    }

    private enum SearchEnd {
        LOWEST, HIGHEST
    }

    private OptionalLong getTimestampMatchingModulus(TimestampRange range, int residue, int modulus, SearchEnd searchEnd) {
        switch (searchEnd) {
            case LOWEST:
                return TimestampRanges.getLowestTimestampMatchingModulus(range, residue, modulus);
            case HIGHEST:
                return TimestampRanges.getHighestTimestampMatchingModulus(range, residue, modulus);
        }
        throw new RuntimeException("unknown type");
    }

}