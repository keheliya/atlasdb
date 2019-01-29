/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.lock.v2;

import java.util.Optional;

import org.immutables.value.Value;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@Value.Immutable
@JsonSerialize(as = ImmutableLeasableLockResponse.class)
@JsonDeserialize(as = ImmutableLeasableLockResponse.class)
public interface LeasableLockResponse {
    @Value.Parameter
    LockResponse getLockResponse();

    @Value.Parameter
    Optional<Lease> getLease();

    static LeasableLockResponse of(LockResponse lockResponse) {
        return ImmutableLeasableLockResponse.of(lockResponse, Optional.empty());
    }

    static LeasableLockResponse of(LockResponse lockResponse, Lease lease) {
        return ImmutableLeasableLockResponse.of(lockResponse, Optional.of(lease));
    }
}