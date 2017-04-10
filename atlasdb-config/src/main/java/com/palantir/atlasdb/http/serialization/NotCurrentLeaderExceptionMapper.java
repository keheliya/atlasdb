/**
 * Copyright 2017 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.http.serialization;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

import com.google.common.net.HttpHeaders;
import com.palantir.leader.NotCurrentLeaderException;

/**
 * Convert {@link NotCurrentLeaderException} into a 503 status response.
 *
 * @author carrino
 */
public class NotCurrentLeaderExceptionMapper implements ExceptionMapper<NotCurrentLeaderException> {
    @Override
    public Response toResponse(NotCurrentLeaderException exception) {
        return Response.serverError()
                .status(503)
                .entity(exception)
                .type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, "0")
                .build();
    }
}
