package com.walmartlabs.concord.plugins.terraform.actions;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
 * -----
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
 * =====
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.plugins.terraform.Constants;
import com.walmartlabs.concord.plugins.terraform.Terraform;
import com.walmartlabs.concord.plugins.terraform.backend.Backend;
import com.walmartlabs.concord.plugins.terraform.commands.ApplyCommand;
import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.MapUtils;

import java.nio.file.Path;
import java.util.Map;

import static com.walmartlabs.concord.plugins.terraform.Utils.getPath;

public class ApplyAction extends Action {

    private final Context ctx;
    private final Map<String, Object> cfg;
    private final boolean debug;
    private final boolean verbose;
    private final Path workDir;
    private final Path dirOrPlan;
    private final Map<String, Object> extraVars;
    private final Map<String, String> env;
    private final boolean ignoreErrors;
    private final boolean saveOutput;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public ApplyAction(Context ctx, Map<String, Object> cfg, Map<String, String> env) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.env = env;

        this.debug = MapUtils.get(cfg, Constants.DEBUG_KEY, false, Boolean.class);
        this.verbose = MapUtils.get(cfg, Constants.VERBOSE_KEY, false, Boolean.class);

        this.workDir = getPath(cfg, com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY, null);
        if (!workDir.isAbsolute()) {
            throw new IllegalArgumentException("'workDir' must be an absolute path, got: " + workDir);
        }

        this.dirOrPlan = getPath(cfg, Constants.DIR_OR_PLAN_KEY, workDir);

        this.extraVars = MapUtils.get(cfg, Constants.EXTRA_VARS_KEY, null, Map.class);
        this.ignoreErrors = MapUtils.get(cfg, Constants.IGNORE_ERRORS_KEY, false, Boolean.class);
        this.saveOutput = MapUtils.get(cfg, Constants.SAVE_OUTPUT_KEY, false, Boolean.class);
        this.objectMapper = new ObjectMapper();
    }

    public ApplyResult exec(Terraform terraform, Backend backend) throws Exception {
        try {
            init(ctx, workDir, dirOrPlan, !verbose, env, terraform, backend);

            Path dirOrPlanAbsolute = workDir.resolve(dirOrPlan);

            Path varsFile = null;
            if (dirOrPlanAbsolute.toFile().isDirectory()) {
                // running without a previously created plan file
                varsFile = createVarsFile(workDir, objectMapper, extraVars);
            }

            Terraform.Result r = new ApplyCommand(debug, workDir, dirOrPlanAbsolute, varsFile, env).exec(terraform);
            if (r.getCode() != 0) {
                throw new RuntimeException("Process finished with code " + r.getCode() + ": " + r.getStderr());
            }

            Map<String, Object> data = null;
            if (saveOutput) {
                OutputResult o = new OutputAction(ctx, cfg, env).exec(terraform, backend);
                if (!o.isOk()) {
                    return ApplyResult.error(o.getError());
                }

                data = o.getData();
            }

            return ApplyResult.ok(r.getStdout(), data);
        } catch (Exception e) {
            if (!ignoreErrors) {
                throw e;
            }

            return ApplyResult.error(e.getMessage());
        }
    }
}
