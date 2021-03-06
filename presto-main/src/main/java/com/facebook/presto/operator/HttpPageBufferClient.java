/*
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
package com.facebook.presto.operator;

import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpClient.HttpResponseFuture;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.HttpUriBuilder;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.ResponseHandler;
import io.airlift.http.client.ResponseTooLargeException;
import io.airlift.log.Logger;
import io.airlift.slice.InputStreamSliceInput;
import io.airlift.slice.SliceInput;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.facebook.presto.PrestoMediaTypes.PRESTO_PAGES_TYPE;
import static com.facebook.presto.block.PagesSerde.readPages;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_BUFFER_COMPLETE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_MAX_SIZE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_PAGE_NEXT_TOKEN;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_PAGE_TOKEN;
import static com.facebook.presto.operator.HttpPageBufferClient.PagesResponse.createEmptyPagesResponse;
import static com.facebook.presto.operator.HttpPageBufferClient.PagesResponse.createPagesResponse;
import static com.facebook.presto.util.Failures.WORKER_NODE_ERROR;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.airlift.http.client.Request.Builder.prepareDelete;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.ResponseHandlerUtils.propagate;
import static io.airlift.http.client.StatusResponseHandler.StatusResponse;
import static io.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public final class HttpPageBufferClient
        implements Closeable
{
    private static final int INITIAL_DELAY_MILLIS = 1;
    private static final int MAX_DELAY_MILLIS = 100;

    private static final Logger log = Logger.get(HttpPageBufferClient.class);

    /**
     * For each request, the addPage method will be called zero or more times,
     * followed by either requestComplete or clientFinished (if buffer complete).  If the client is
     * closed, requestComplete or bufferFinished may never be called.
     * <p/>
     * <b>NOTE:</b> Implementations of this interface are not allowed to perform
     * blocking operations.
     */
    public interface ClientCallback
    {
        void addPage(HttpPageBufferClient client, Page page);

        void requestComplete(HttpPageBufferClient client);

        void clientFinished(HttpPageBufferClient client);

        void clientFailed(HttpPageBufferClient client, Throwable cause);
    }

    private final HttpClient httpClient;
    private final DataSize maxResponseSize;
    private final Duration minErrorDuration;
    private final URI location;
    private final ClientCallback clientCallback;
    private final BlockEncodingSerde blockEncodingSerde;
    private final ScheduledExecutorService executor;

    @GuardedBy("this")
    private final Stopwatch errorStopwatch;

    @GuardedBy("this")
    private boolean closed;
    @GuardedBy("this")
    private HttpResponseFuture<?> future;
    @GuardedBy("this")
    private DateTime lastUpdate = DateTime.now();
    @GuardedBy("this")
    private long token;
    @GuardedBy("this")
    private boolean scheduled;
    @GuardedBy("this")
    private boolean completed;
    @GuardedBy("this")
    private long errorDelayMillis;

    private final AtomicInteger pagesReceived = new AtomicInteger();

    private final AtomicInteger requestsScheduled = new AtomicInteger();
    private final AtomicInteger requestsCompleted = new AtomicInteger();
    private final AtomicInteger requestsFailed = new AtomicInteger();

    public HttpPageBufferClient(
            HttpClient httpClient,
            DataSize maxResponseSize,
            Duration minErrorDuration,
            URI location,
            ClientCallback clientCallback,
            BlockEncodingSerde blockEncodingSerde,
            ScheduledExecutorService executor)
    {
        this(httpClient, maxResponseSize, minErrorDuration, location, clientCallback, blockEncodingSerde, executor, Stopwatch.createUnstarted());
    }

    public HttpPageBufferClient(
            HttpClient httpClient,
            DataSize maxResponseSize,
            Duration minErrorDuration,
            URI location,
            ClientCallback clientCallback,
            BlockEncodingSerde blockEncodingSerde,
            ScheduledExecutorService executor,
            Stopwatch errorStopwatch)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.maxResponseSize = requireNonNull(maxResponseSize, "maxResponseSize is null");
        this.minErrorDuration = requireNonNull(minErrorDuration, "minErrorDuration is null");
        this.location = requireNonNull(location, "location is null");
        this.clientCallback = requireNonNull(clientCallback, "clientCallback is null");
        this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingManager is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.errorStopwatch = requireNonNull(errorStopwatch, "errorStopwatch is null").reset();
    }

    public synchronized PageBufferClientStatus getStatus()
    {
        String state;
        if (closed) {
            state = "closed";
        }
        else if (future != null) {
            state = "running";
        }
        else if (scheduled) {
            state = "scheduled";
        }
        else if (completed) {
            state = "completed";
        }
        else {
            state = "queued";
        }
        String httpRequestState = "not scheduled";
        if (future != null) {
            httpRequestState = future.getState();
        }
        return new PageBufferClientStatus(
                location,
                state,
                lastUpdate,
                pagesReceived.get(),
                requestsScheduled.get(),
                requestsCompleted.get(),
                requestsFailed.get(),
                httpRequestState);
    }

    public synchronized boolean isRunning()
    {
        return future != null;
    }

    @Override
    public void close()
    {
        boolean shouldSendDelete;
        Future<?> future;
        synchronized (this) {
            shouldSendDelete = !closed;

            closed = true;

            future = this.future;

            this.future = null;

            lastUpdate = DateTime.now();
        }

        if (future != null) {
            future.cancel(true);
        }

        // abort the output buffer on the remote node; response of delete is ignored
        if (shouldSendDelete) {
            httpClient.executeAsync(prepareDelete().setUri(location).build(), createStatusResponseHandler());
        }
    }

    public synchronized void scheduleRequest()
    {
        if (closed || (future != null) || scheduled) {
            return;
        }
        scheduled = true;

        // start before scheduling to include error delay
        errorStopwatch.start();

        executor.schedule(() -> {
            try {
                initiateRequest();
            }
            catch (Throwable t) {
                // should not happen, but be safe and fail the operator
                clientCallback.clientFailed(HttpPageBufferClient.this, t);
            }
        }, errorDelayMillis, TimeUnit.MILLISECONDS);

        lastUpdate = DateTime.now();
        requestsScheduled.incrementAndGet();
    }

    private synchronized void initiateRequest()
    {
        scheduled = false;
        if (closed || (future != null)) {
            return;
        }

        if (completed) {
            sendDelete();
        }
        else {
            sendGetResults();
        }

        lastUpdate = DateTime.now();
    }

    private void sendGetResults()
    {
        final URI uri = HttpUriBuilder.uriBuilderFrom(location).appendPath(String.valueOf(token)).build();
        HttpResponseFuture<PagesResponse> resultFuture = httpClient.executeAsync(
                prepareGet()
                        .setHeader(PRESTO_MAX_SIZE, maxResponseSize.toString())
                        .setUri(uri).build(),
                new PageResponseHandler(blockEncodingSerde));

        future = resultFuture;
        Futures.addCallback(resultFuture, new FutureCallback<PagesResponse>()
        {
            @Override
            public void onSuccess(PagesResponse result)
            {
                checkNotHoldsLock();

                resetErrors();

                List<Page> pages;
                synchronized (HttpPageBufferClient.this) {
                    if (result.getToken() == token) {
                        pages = result.getPages();
                        token = result.getNextToken();
                    }
                    else {
                        pages = ImmutableList.of();
                    }
                }

                // add pages
                for (Page page : pages) {
                    pagesReceived.incrementAndGet();
                    clientCallback.addPage(HttpPageBufferClient.this, page);
                }

                synchronized (HttpPageBufferClient.this) {
                    // client is complete, acknowledge it by sending it a delete in the next request
                    if (result.isClientComplete()) {
                        completed = true;
                    }
                    future = null;
                    lastUpdate = DateTime.now();

                }
                requestsCompleted.incrementAndGet();
                clientCallback.requestComplete(HttpPageBufferClient.this);
            }

            @Override
            public void onFailure(Throwable t)
            {
                log.debug("Request to %s failed %s", uri, t);
                checkNotHoldsLock();

                Duration errorDuration = elapsedErrorDuration();

                t = rewriteException(t);
                if (!(t instanceof PrestoException) && errorDuration.compareTo(minErrorDuration) > 0) {
                    String message = format("%s (%s - requests failed for %s)", WORKER_NODE_ERROR, uri, errorDuration);
                    t = new PageTransportTimeoutException(message, t);
                }
                handleFailure(t);
            }
        }, executor);
    }

    private void sendDelete()
    {
        HttpResponseFuture<StatusResponse> resultFuture = httpClient.executeAsync(prepareDelete().setUri(location).build(), createStatusResponseHandler());
        future = resultFuture;
        Futures.addCallback(resultFuture, new FutureCallback<StatusResponse>()
        {
            @Override
            public void onSuccess(@Nullable StatusResponse result)
            {
                checkNotHoldsLock();
                synchronized (HttpPageBufferClient.this) {
                    closed = true;
                    future = null;
                    lastUpdate = DateTime.now();
                }
                requestsCompleted.incrementAndGet();
                clientCallback.clientFinished(HttpPageBufferClient.this);
            }

            @Override
            public void onFailure(Throwable t)
            {
                checkNotHoldsLock();

                log.error("Request to delete %s failed %s", location, t);
                Duration errorDuration = elapsedErrorDuration();
                if (!(t instanceof PrestoException) && errorDuration.compareTo(minErrorDuration) > 0) {
                    String message = format("%s (%s - requests failed for %s)", WORKER_NODE_ERROR, location, errorDuration);
                    t = new PrestoException(StandardErrorCode.TOO_MANY_REQUESTS_FAILED, message, t);
                }
                handleFailure(t);
            }
        }, executor);
    }

    private void checkNotHoldsLock()
    {
        if (Thread.holdsLock(HttpPageBufferClient.this)) {
            log.error("Can not handle callback while holding a lock on this");
        }
    }

    private void handleFailure(Throwable t)
    {
        requestsFailed.incrementAndGet();
        requestsCompleted.incrementAndGet();

        if (t instanceof PrestoException) {
            clientCallback.clientFailed(HttpPageBufferClient.this, t);
        }

        increaseErrorDelay();
        synchronized (HttpPageBufferClient.this) {
            future = null;
            lastUpdate = DateTime.now();
        }
        clientCallback.requestComplete(HttpPageBufferClient.this);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HttpPageBufferClient that = (HttpPageBufferClient) o;

        if (!location.equals(that.location)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        return location.hashCode();
    }

    @Override
    public String toString()
    {
        String state;
        synchronized (this) {
            if (closed) {
                state = "CLOSED";
            }
            else if (future != null) {
                state = "RUNNING";
            }
            else {
                state = "QUEUED";
            }
        }
        return toStringHelper(this)
                .add("location", location)
                .addValue(state)
                .toString();
    }

    private static Throwable rewriteException(Throwable t)
    {
        if (t instanceof ResponseTooLargeException) {
            return new PageTooLargeException();
        }
        return t;
    }

    private synchronized Duration elapsedErrorDuration()
    {
        if (errorStopwatch.isRunning()) {
            errorStopwatch.stop();
        }
        long nanos = errorStopwatch.elapsed(TimeUnit.NANOSECONDS);
        return new Duration(nanos, TimeUnit.NANOSECONDS).convertTo(TimeUnit.SECONDS);
    }

    private synchronized void increaseErrorDelay()
    {
        if (errorDelayMillis == 0) {
            errorDelayMillis = INITIAL_DELAY_MILLIS;
        }
        else {
            errorDelayMillis = min(errorDelayMillis * 2, MAX_DELAY_MILLIS);
        }
    }

    private synchronized void resetErrors()
    {
        errorStopwatch.reset();
    }

    public static class PageResponseHandler
            implements ResponseHandler<PagesResponse, RuntimeException>
    {
        private final BlockEncodingSerde blockEncodingSerde;

        public PageResponseHandler(BlockEncodingSerde blockEncodingSerde)
        {
            this.blockEncodingSerde = blockEncodingSerde;
        }

        @Override
        public PagesResponse handleException(Request request, Exception exception)
        {
            throw propagate(request, exception);
        }

        @Override
        public PagesResponse handle(Request request, Response response)
        {
            // no content means no content was created within the wait period, but query is still ok
            // if job is finished, complete is set in the response
            if (response.getStatusCode() == HttpStatus.NO_CONTENT.code()) {
                return createEmptyPagesResponse(getToken(response), getNextToken(response), getComplete(response));
            }

            // otherwise we must have gotten an OK response, everything else is considered fatal
            if (response.getStatusCode() != HttpStatus.OK.code()) {
                throw new PageTransportErrorException(format("Expected response code to be 200, but was %s %s: %s", response.getStatusCode(), response.getStatusMessage(), request.getUri()));
            }

            String contentType = response.getHeader(CONTENT_TYPE);
            if ((contentType == null) || !mediaTypeMatches(contentType, PRESTO_PAGES_TYPE)) {
                // this can happen when an error page is returned, but is unlikely given the above 200
                throw new PageTransportErrorException(format("Expected %s response from server but got %s: %s", PRESTO_PAGES_TYPE, contentType, request.getUri()));
            }

            long token = getToken(response);
            long nextToken = getNextToken(response);
            boolean complete = getComplete(response);

            try (SliceInput input = new InputStreamSliceInput(response.getInputStream())) {
                List<Page> pages = ImmutableList.copyOf(readPages(blockEncodingSerde, input));
                return createPagesResponse(token, nextToken, pages, complete);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }

        private static long getToken(Response response)
        {
            String tokenHeader = response.getHeader(PRESTO_PAGE_TOKEN);
            if (tokenHeader == null) {
                throw new PageTransportErrorException(format("Expected %s header", PRESTO_PAGE_TOKEN));
            }
            return Long.parseLong(tokenHeader);
        }

        private static long getNextToken(Response response)
        {
            String nextTokenHeader = response.getHeader(PRESTO_PAGE_NEXT_TOKEN);
            if (nextTokenHeader == null) {
                throw new PageTransportErrorException(format("Expected %s header", PRESTO_PAGE_NEXT_TOKEN));
            }
            return Long.parseLong(nextTokenHeader);
        }

        private static boolean getComplete(Response response)
        {
            String bufferComplete = response.getHeader(PRESTO_BUFFER_COMPLETE);
            if (bufferComplete == null) {
                throw new PageTransportErrorException(format("Expected %s header", PRESTO_BUFFER_COMPLETE));
            }
            return Boolean.parseBoolean(bufferComplete);
        }

        private static boolean mediaTypeMatches(String value, MediaType range)
        {
            try {
                return MediaType.parse(value).is(range);
            }
            catch (IllegalArgumentException | IllegalStateException e) {
                return false;
            }
        }
    }

    public static class PagesResponse
    {
        public static PagesResponse createPagesResponse(long token, long nextToken, Iterable<Page> pages, boolean complete)
        {
            return new PagesResponse(token, nextToken, pages, complete);
        }

        public static PagesResponse createEmptyPagesResponse(long token, long nextToken, boolean complete)
        {
            return new PagesResponse(token, nextToken, ImmutableList.<Page>of(), complete);
        }

        private final long token;
        private final long nextToken;
        private final List<Page> pages;
        private final boolean clientComplete;

        private PagesResponse(long token, long nextToken, Iterable<Page> pages, boolean clientComplete)
        {
            this.token = token;
            this.nextToken = nextToken;
            this.pages = ImmutableList.copyOf(pages);
            this.clientComplete = clientComplete;
        }

        public long getToken()
        {
            return token;
        }

        public long getNextToken()
        {
            return nextToken;
        }

        public List<Page> getPages()
        {
            return pages;
        }

        public boolean isClientComplete()
        {
            return clientComplete;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("token", token)
                    .add("nextToken", nextToken)
                    .add("pagesSize", pages.size())
                    .add("clientComplete", clientComplete)
                    .toString();
        }

    }
}
