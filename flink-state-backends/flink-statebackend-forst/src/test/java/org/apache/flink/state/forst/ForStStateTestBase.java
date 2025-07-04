/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.forst;

import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.asyncprocessing.EpochManager;
import org.apache.flink.runtime.asyncprocessing.RecordContext;
import org.apache.flink.runtime.asyncprocessing.StateExecutionController;
import org.apache.flink.runtime.asyncprocessing.declare.DeclarationManager;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.runtime.state.CheckpointStorageAccess;
import org.apache.flink.runtime.state.filesystem.FsCheckpointStorageAccess;
import org.apache.flink.runtime.util.TestingTaskManagerRuntimeInfo;
import org.apache.flink.streaming.runtime.tasks.StreamTaskActionExecutor;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorImpl;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailboxImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.apache.flink.state.forst.ForStTestUtils.createKeyedStateBackend;

/**
 * A base class for all forst state tests, providing building the AEC and StateBackend logic, and
 * some tool methods.
 */
public class ForStStateTestBase {

    protected ForStKeyedStateBackend<String> keyedBackend;

    protected StateExecutionController<String> aec;

    protected MailboxExecutor mailboxExecutor;

    protected RecordContext<String> context;

    protected MockEnvironment env;

    @BeforeEach
    public void setup(@TempDir File temporaryFolder) throws IOException {
        FileSystem.initialize(new Configuration(), null);
        Configuration configuration = new Configuration();
        configuration.set(ForStOptions.PRIMARY_DIRECTORY, temporaryFolder.toURI().toString());
        ForStStateBackend forStStateBackend =
                new ForStStateBackend().configure(configuration, null);

        env = getMockEnvironment(temporaryFolder);

        keyedBackend = createKeyedStateBackend(forStStateBackend, env, StringSerializer.INSTANCE);

        mailboxExecutor =
                new MailboxExecutorImpl(
                        new TaskMailboxImpl(), 0, StreamTaskActionExecutor.IMMEDIATE);

        aec =
                new StateExecutionController<>(
                        mailboxExecutor,
                        (a, b) -> {},
                        keyedBackend.createStateExecutor(),
                        new DeclarationManager(),
                        EpochManager.ParallelMode.SERIAL_BETWEEN_EPOCH,
                        1,
                        100,
                        0,
                        1,
                        null,
                        null);
        keyedBackend.setup(aec);
    }

    @AfterEach
    void tearDown() throws Exception {
        keyedBackend.close();
    }

    protected void setCurrentContext(Object record, String key) {
        context = aec.buildContext(record, key);
        context.retain();
        aec.setCurrentContext(context);
    }

    protected void drain() {
        context.release();
        aec.drainInflightRecords(0);
    }

    protected static MockEnvironment getMockEnvironment(File tempDir) throws IOException {
        MockEnvironment env =
                MockEnvironment.builder()
                        .setUserCodeClassLoader(ForStStateBackendConfigTest.class.getClassLoader())
                        .setTaskManagerRuntimeInfo(
                                new TestingTaskManagerRuntimeInfo(new Configuration(), tempDir))
                        .build();

        CheckpointStorageAccess checkpointStorageAccess =
                new FsCheckpointStorageAccess(
                        new Path(tempDir.getPath(), "checkpoint"),
                        null,
                        env.getJobID(),
                        1024,
                        4096);
        env.setCheckpointStorageAccess(checkpointStorageAccess);
        return env;
    }
}
