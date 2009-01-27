/*
 * The contents of this file are subject to the terms 
 * of the Common Development and Distribution License 
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at 
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing 
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL 
 * Header Notice in each file and include the License file 
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.  
 * If applicable, add the following below the CDDL Header, 
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package org.glassfish.deployment.admin;

import org.glassfish.server.ServerEnvironmentImpl;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ServerEnvironment;
import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.ApplicationRef;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.v3.server.ApplicationLifecycle;
import com.sun.enterprise.util.LocalStringManagerImpl;
import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.deployment.common.DeploymentContextImpl;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.data.ApplicationInfo;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.beans.PropertyVetoException;

/**
 * Disable command
 */
@Service(name="disable")
@I18n("disable.command")
@Scoped(PerLookup.class)
    
public class DisableCommand implements AdminCommand {

    final private static LocalStringManagerImpl localStrings = new LocalStringManagerImpl(DisableCommand.class);    

    @Inject
    ServerEnvironmentImpl env;

    @Inject
    Deployment deployment;

    @Inject(name= ServerEnvironment.DEFAULT_INSTANCE_NAME)
    protected Server server;    

    @Param(primary=true)
    String component = null;

    @Param(optional=true)    
    String target = "server";

    /**
     * Entry point from the framework into the command execution
     * @param context context for the command.
     */
    public void execute(AdminCommandContext context) {

        final ActionReport report = context.getActionReport();
        final Logger logger = context.getLogger();

        ApplicationInfo appInfo = deployment.get(component);
        if (appInfo==null) {
            report.setMessage(localStrings.getLocalString("application.notreg","Application {0} not registered", component));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        // return if the application is already in disabled state
        if (!Boolean.valueOf(ConfigBeansUtilities.getEnabled(target,
            component))) {
            logger.fine("The application is already disabled");
            return;
        }

        try {
            final DeploymentContextImpl deploymentContext =
                new DeploymentContextImpl(logger, null, context.getCommandParameters(), env);


            appInfo.unload(deploymentContext, report);

            if (report.getActionExitCode().equals(
                ActionReport.ExitCode.SUCCESS)) {
            for (ApplicationRef ref : server.getApplicationRef()) {
                if (ref.getRef().equals(component)) {
                    ConfigSupport.apply(new SingleConfigCode<ApplicationRef>() {
                        public Object run(ApplicationRef param) throws
                                PropertyVetoException, TransactionFailure {
                            param.setEnabled(String.valueOf(false));
                            return null;
                        }
                    }, ref);
                    break;
                }
            }
            }

        } catch(Exception e) {
            logger.log(Level.SEVERE, "Error during disabling: ", e);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(e.getMessage());
        }
    }        
}
