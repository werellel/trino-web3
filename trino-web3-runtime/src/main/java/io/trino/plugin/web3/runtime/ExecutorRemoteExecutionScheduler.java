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
package io.trino.plugin.web3.runtime;

import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ExecutorRemoteExecutionScheduler
        implements RemoteExecutionScheduler
{
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    public ExecutorRemoteExecutionScheduler()
    {
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public long nanoTime()
    {
        return System.nanoTime();
    }

    @Override
    public ZonedDateTime currentTime()
    {
        return ZonedDateTime.now();
    }

    @Override
    public void schedule(Runnable task, long delay, TimeUnit unit)
    {
        executor.schedule(task, delay, unit);
    }

    @Override
    public void close()
    {
        executor.shutdownNow();
    }
}
