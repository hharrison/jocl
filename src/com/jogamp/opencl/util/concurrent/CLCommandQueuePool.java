/*
 * Created on Tuesday, May 03 2011
 */
package com.jogamp.opencl.util.concurrent;

import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLResource;
import com.jogamp.opencl.util.CLMultiContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A multithreaded, fixed size pool of OpenCL command queues.
 * CLCommandQueuePool serves as a multiplexer distributing tasks over N queues usually connected to N devices.
 * The usage of this pool is similar to {@link ExecutorService} but it uses {@link CLTask}s
 * instead of {@link Callable}s and provides a per-queue context for resource sharing across all tasks of one queue.
 * @author Michael Bien
 */
public class CLCommandQueuePool<C extends CLQueueContext> implements CLResource {

    private List<CLQueueContext> contexts;
    private ThreadPoolExecutor excecutor;
    private FinishAction finishAction = FinishAction.DO_NOTHING;
    private boolean released;

    private CLCommandQueuePool(CLQueueContextFactory factory, Collection<CLCommandQueue> queues) {
        this.contexts = initContexts(queues, factory);
        initExecutor();
    }

    private List<CLQueueContext> initContexts(Collection<CLCommandQueue> queues, CLQueueContextFactory factory) {
        List<CLQueueContext> newContexts = new ArrayList<CLQueueContext>(queues.size());
        
        int index = 0;
        for (CLCommandQueue queue : queues) {
            
            CLQueueContext old = null;
            if(this.contexts != null && !this.contexts.isEmpty()) {
                old = this.contexts.get(index++);
                old.release();
            }
            
            newContexts.add(factory.setup(queue, old));
        }
        return newContexts;
    }

    private void initExecutor() {
        BlockingQueue<Runnable> queue = new LinkedBlockingDeque<Runnable>();
        QueueThreadFactory factory = new QueueThreadFactory(contexts);
        int size = contexts.size();
        this.excecutor = new CLThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, queue, factory);
    }

    public static <C extends CLQueueContext> CLCommandQueuePool<C> create(CLQueueContextFactory<C> factory, CLMultiContext mc, CLCommandQueue.Mode... modes) {
        return create(factory, mc.getDevices(), modes);
    }

    public static <C extends CLQueueContext> CLCommandQueuePool<C> create(CLQueueContextFactory<C> factory, Collection<CLDevice> devices, CLCommandQueue.Mode... modes) {
        List<CLCommandQueue> queues = new ArrayList<CLCommandQueue>(devices.size());
        for (CLDevice device : devices) {
            queues.add(device.createCommandQueue(modes));
        }
        return create(factory, queues);
    }

    public static <C extends CLQueueContext> CLCommandQueuePool create(CLQueueContextFactory<C> factory, Collection<CLCommandQueue> queues) {
        return new CLCommandQueuePool(factory, queues);
    }

    /**
     * Submits this task to the pool for execution returning its {@link Future}.
     * @see ExecutorService#submit(java.util.concurrent.Callable)
     */
    public <R> Future<R> submit(CLTask<? super C, R> task) {
        return excecutor.submit(wrapTask(task));
    }

    /**
     * Submits all tasks to the pool for execution and returns their {@link Future}.
     * Calls {@link #submit(com.jogamp.opencl.util.concurrent.CLTask)} for every task.
     */
    public <R> List<Future<R>> submitAll(Collection<? extends CLTask<? super C, R>> tasks) {
        List<Future<R>> futures = new ArrayList<Future<R>>(tasks.size());
        for (CLTask<? super C, R> task : tasks) {
            futures.add(submit(task));
        }
        return futures;
    }

    /**
     * Submits all tasks to the pool for immediate execution (blocking) and returns their {@link Future} holding the result.
     * @see ExecutorService#invokeAll(java.util.Collection) 
     */
    public <R> List<Future<R>> invokeAll(Collection<? extends CLTask<? super C, R>> tasks) throws InterruptedException {
        List<TaskWrapper<C, R>> wrapper = wrapTasks(tasks);
        return excecutor.invokeAll(wrapper);
    }

    /**
     * Submits all tasks to the pool for immediate execution (blocking) and returns their {@link Future} holding the result.
     * @see ExecutorService#invokeAll(java.util.Collection, long, java.util.concurrent.TimeUnit)
     */
    public <R> List<Future<R>> invokeAll(Collection<? extends CLTask<? super C, R>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        List<TaskWrapper<C, R>> wrapper = wrapTasks(tasks);
        return excecutor.invokeAll(wrapper, timeout, unit);
    }

    /**
     * Submits all tasks for immediate execution (blocking) until a result can be returned.
     * All other unfinished but started tasks are cancelled.
     * @see ExecutorService#invokeAny(java.util.Collection)
     */
    public <R> R invokeAny(Collection<? extends CLTask<? super C, R>> tasks) throws InterruptedException, ExecutionException {
        List<TaskWrapper<C, R>> wrapper = wrapTasks(tasks);
        return excecutor.invokeAny(wrapper);
    }

    /*public*/ CLTask<? super C, ?> takeCLTask() throws InterruptedException {
        return ((CLFutureTask<? super C, ?>)excecutor.getQueue().take()).getCLTask();
    }

    /**
     * Submits all tasks for immediate execution (blocking) until a result can be returned.
     * All other unfinished but started tasks are cancelled.
     * @see ExecutorService#invokeAny(java.util.Collection, long, java.util.concurrent.TimeUnit)
     */
    public <R> R invokeAny(Collection<? extends CLTask<? super C, R>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        List<TaskWrapper<C, R>> wrapper = wrapTasks(tasks);
        return excecutor.invokeAny(wrapper, timeout, unit);
    }

    <R> TaskWrapper<C, R> wrapTask(CLTask<? super C, R> task) {
        return new TaskWrapper(task, finishAction);
    }

    private <R> List<TaskWrapper<C, R>> wrapTasks(Collection<? extends CLTask<? super C, R>> tasks) {
        List<TaskWrapper<C, R>> wrapper = new ArrayList<TaskWrapper<C, R>>(tasks.size());
        for (CLTask<? super C, R> task : tasks) {
            if(task == null) {
                throw new NullPointerException("at least one task was null");
            }
            wrapper.add(new TaskWrapper<C, R>(task, finishAction));
        }
        return wrapper;
    }
    
    /**
     * Switches the context of all queues - this operation can be expensive.
     * Blocks until all tasks finish and sets up a new context for all queues.
     * @return this
     */
    public <C extends CLQueueContext> CLCommandQueuePool switchContext(CLQueueContextFactory<C> factory) {
        
        excecutor.shutdown();
        finishQueues(); // just to be sure
        
        contexts = initContexts(getQueues(), factory);
        initExecutor();
        return this;
    }

    /**
     * Calls {@link CLCommandQueue#flush()} on all queues.
     */
    public void flushQueues() {
        for (CLQueueContext context : contexts) {
            context.queue.flush();
        }
    }

    /**
     * Calls {@link CLCommandQueue#finish()} on all queues.
     */
    public void finishQueues() {
        for (CLQueueContext context : contexts) {
            context.queue.finish();
        }
    }

    /**
     * Releases the queue context, all queues including a shutdown of the internal threadpool.
     * The call will block until all currently executing tasks have finished, no new tasks are started.
     */
    @Override
    public void release() {
        if(released) {
            throw new RuntimeException(getClass().getSimpleName()+" already released");
        }
        released = true;
        excecutor.shutdownNow();
        try {
            excecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }finally{
            for (CLQueueContext context : contexts) {
                context.queue.finish().release();
                context.release();
            }
        }
    }

    ExecutorService getExcecutor() {
        return excecutor;
    }

    /**
     * Returns the command queues used in this pool.
     */
    public List<CLCommandQueue> getQueues() {
        List<CLCommandQueue> queues = new ArrayList<CLCommandQueue>(contexts.size());
        for (CLQueueContext context : contexts) {
            queues.add(context.queue);
        }
        return queues;
    }

    /**
     * Returns the size of this pool (number of command queues).
     */
    public int getPoolSize() {
        return contexts.size();
    }

    /**
     * Returns the action which is executed when a task finishes.
     */
    public FinishAction getFinishAction() {
        return finishAction;
    }

    /**
     * Returns the approximate total number of tasks that have ever been scheduled for execution.
     * Because the states of tasks and threads may change dynamically during computation, the returned
     * value is only an approximation.
     */
    public long getTaskCount() {
        return excecutor.getTaskCount();
    }

    /**
     * Returns the approximate total number of tasks that have completed execution.
     * Because the states of tasks and threads may change dynamically during computation,
     * the returned value is only an approximation, but one that does not ever decrease across successive calls.
     */
    public long getCompletedTaskCount() {
        return excecutor.getCompletedTaskCount();
    }

    /**
     * Returns the approximate number of queues that are actively executing tasks.
     */
    public int getActiveCount() {
        return excecutor.getActiveCount();
    }

    @Override
    public boolean isReleased() {
        return released;
    }

    /**
     * Sets the action which is run after every completed task.
     * This is mainly intended for debugging, default value is {@link FinishAction#DO_NOTHING}.
     */
    public void setFinishAction(FinishAction action) {
        this.finishAction = action;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+" [queues: "+contexts.size()+" on finish: "+finishAction+"]";
    }

    private static class QueueThreadFactory implements ThreadFactory {

        private final List<CLQueueContext> context;
        private int index;

        private QueueThreadFactory(List<CLQueueContext> queues) {
            this.context = queues;
            this.index = 0;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {

            SecurityManager sm = System.getSecurityManager();
            ThreadGroup group = (sm != null) ? sm.getThreadGroup() : Thread.currentThread().getThreadGroup();

            CLQueueContext queue = context.get(index);
            QueueThread thread = new QueueThread(group, runnable, queue, index++);
            thread.setDaemon(true);
            
            return thread;
        }

    }
    
    private static class QueueThread extends Thread {
        private final CLQueueContext context;
        public QueueThread(ThreadGroup group, Runnable runnable, CLQueueContext context, int index) {
            super(group, runnable, "queue-worker-thread-"+index+"["+context+"]");
            this.context = context;
        }
    }

    private static class TaskWrapper<C extends CLQueueContext, R> implements Callable<R> {

        private final CLTask<? super C, R> task;
        private final FinishAction mode;
        
        private TaskWrapper(CLTask<? super C, R> task, FinishAction mode) {
            this.task = task;
            this.mode = mode;
        }

        @Override
        public R call() throws Exception {
            CLQueueContext context = ((QueueThread)Thread.currentThread()).context;
            R result = task.execute((C)context);
            if(mode.equals(FinishAction.FLUSH)) {
                context.queue.flush();
            }else if(mode.equals(FinishAction.FINISH)) {
                context.queue.finish();
            }
            return result;
        }

    }

    private static class CLFutureTask<C extends CLQueueContext, R> extends FutureTask<R> {

        private final TaskWrapper<C, R> wrapper;

        public CLFutureTask(TaskWrapper<C, R> wrapper) {
            super(wrapper);
            this.wrapper = wrapper;
        }

        public CLTask<? super C, R> getCLTask() {
            return wrapper.task;
        }

    }

    private static class CLThreadPoolExecutor extends ThreadPoolExecutor {

        public CLThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            TaskWrapper<CLQueueContext, T> wrapper = (TaskWrapper<CLQueueContext, T>)callable;
            return new CLFutureTask<CLQueueContext, T>(wrapper);
        }

    }

    /**
     * The action executed after a task completes.
     */
    public enum FinishAction {

        /**
         * Does nothing, the task is responsible to make sure all computations
         * have finished when the task finishes
         */
        DO_NOTHING,

        /**
         * Flushes the queue on task completion.
         */
        FLUSH,
        
        /**
         * Finishes the queue on task completion.
         */
        FINISH
    }

}
