/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.admin.cluster;

import java.io.*;
import java.util.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.xml.bind.*;

import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;

import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.ActionReport;
import org.glassfish.api.ActionReport.ExitCode;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.admin.Payload;
import org.glassfish.api.admin.config.ApplicationName;
import com.sun.enterprise.config.serverbeans.Applications;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.ApplicationRef;
import com.sun.enterprise.config.serverbeans.Servers;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.ServerRef;
import com.sun.enterprise.config.serverbeans.Clusters;
import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.util.cluster.SyncRequest;
import com.sun.enterprise.util.cluster.SyncRequest.ModTime;
import com.sun.enterprise.util.LocalStringManagerImpl;

/**
 * Synchronize files.  Accepts an XML document containing files
 * and mod times and sends the client new versions of anything
 * that's out of date.
 *
 * @author Bill Shannon
 */
@Service(name="_synchronize-files")
@I18n("synchronize.command")
public class SynchronizeFiles implements AdminCommand {

    @Param(name = "file_list", primary = true)
    private File fileList;

    @Param(name = "syncarchive", optional = true)
    private boolean syncArchive;

    @Param(name = "allapps", optional = true, defaultValue = "true")
    private boolean allApps = true;

    @Inject
    private ServerEnvironment env;

    @Inject(optional = true)
    private Applications applications;

    @Inject(optional = true)
    private Clusters clusters;

    @Inject(optional = true)
    private Servers servers;

    private URI domainRootUri;  // URI of the domain's root directory

    private Logger logger;

    /*
    private static final boolean syncArchive = Boolean.parseBoolean(
                        System.getProperty("copy.inplace.archive", "true"));
    */

    private final static LocalStringManagerImpl strings =
        new LocalStringManagerImpl(SynchronizeFiles.class);

    public void execute(AdminCommandContext context) {
        ActionReport report = context.getActionReport();
        logger = context.getLogger();
System.out.println("SynchronizeFiles: logger " + logger.getName());
logger.setLevel(Level.FINEST);
        domainRootUri = env.getDomainRoot().toURI();
        try {
            /*
            try {
            BufferedInputStream in =
                new BufferedInputStream(new FileInputStream(fileList));
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            System.out.write(buf, 0, n);
            in.close();
            } catch (IOException ex) {}
            */

            // read the input document
            JAXBContext jc = JAXBContext.newInstance(SyncRequest.class);
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            unmarshaller.setSchema(null);       // XXX - needed?
            SyncRequest sr = (SyncRequest)unmarshaller.unmarshal(fileList);
            logger.finer("SynchronizeFiles: synchronize dir " + sr.dir);

            // verify the server instance is valid
            Server server = null;
            if (servers != null)
                server = servers.getServer(sr.instance);
            if (server == null) {
                // XXX - switch this once create-instance is working
                /*
                report.setActionExitCode(ExitCode.FAILURE);
                report.setMessage("Unknown server instance: " + sr.instance); // XXX I18N
                return;
                */
                logger.fine("SynchronizeFiles: instance unknown: " + sr.instance);
                server = servers.getServer("server");
            }

            // handle the request appropriately based on the directory
            if (sr.dir.equals("config"))
                synchronizeConfig(context, sr);
            else if (sr.dir.equals("applications"))
                synchronizeApplications(context, server, sr);
            /*
            else if (sr.dir.equals("lib"))
                ; // XXX
            else if (sr.dir.equals("docroot"))
                ; // XXX
            */
            else {
                report.setActionExitCode(ExitCode.FAILURE);
                report.setMessage("Unknown directory: " + sr.dir); // XXX I18N
                return;
            }
        } catch (JAXBException jex) {
            logger.fine("SynchronizeFiles: JAXBException reading request");
            logger.fine(jex.toString());
            // ignore for now
        }
        report.setActionExitCode(ExitCode.SUCCESS);
    }

    // XXX - should be in a resource file
    private String[] configFiles = {
        "domain.xml",
        "admin-keyfile",
        "cacerts.jks",
        "default-web.xml",
        "domain-passwords",
        "keyfile",
        "keystore.jks",
        "logging.properties",
        "login.conf",
        "server.policy",
        "sun-acc.xml",
        "wss-server-config-1.0.xml",
        "wss-server-config-2.0.xml"
    };

    /**
     * Synchronize files in the config directory.
     * If the domain.xml file is up to date, don't worry
     * about any of the other files.
     */
    private void synchronizeConfig(AdminCommandContext context,
                                    SyncRequest sr) {
        logger.finer("SynchronizeFiles: synchronize config");
        // find the domain.xml entry
        ModTime domainXmlMT = null;
        for (ModTime mt : sr.files) {
            if (mt.name.equals("domain.xml")) {
                domainXmlMT = mt;
                break;
            }
        }
        if (domainXmlMT == null)        // couldn't find it, fake it
            domainXmlMT = new ModTime("domain.xml", 0);

        File configDir = env.getConfigDirPath();
        Payload.Outbound outboundPayload = context.getOutboundPayload();
        if (!syncFile(configDir, domainXmlMT, outboundPayload)) {
            logger.finer("SynchronizeFiles: domain.xml HAS NOT CHANGED");
            return;
        }

        // get the set of all the config files we need to consider
        Set<String> configFileSet = getConfigFileNames();
        configFileSet.remove("domain.xml");     // already handled it

        for (ModTime mt : sr.files) {
            if (mt.name.equals("domain.xml"))   // did domain.xml above
                continue;
            if (configFileSet.contains(mt.name)) {
                // if client has file, remove it from set
                configFileSet.remove(mt.name);
                syncFile(configDir, mt, outboundPayload);
            } else
                removeFile(configDir, mt, outboundPayload);
        }

        // now do all the remaining files the client doesn't have
        for (String name : configFileSet)
            syncFile(configDir, new ModTime(name, 0), outboundPayload);
    }

    /**
     * Return the names of the config files we need to consider.
     * Names are all relative to the config directory.
     */
    private Set<String> getConfigFileNames() {
        Set<String> files = new LinkedHashSet<String>();
        BufferedReader in = null;
        try {
            File configDir = env.getConfigDirPath();
            File f = new File(configDir, "config-files");
            // if the file doesn't exist, use a build-in default
            // XXX - convert this into a resource in the jar file
            if (!f.exists())
                return new LinkedHashSet<String>(Arrays.asList(configFiles));
            in = new BufferedReader(new InputStreamReader(
                                                new FileInputStream(f)));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                files.add(line.trim());
            }
        } catch (IOException ex) {
            logger.fine("SynchronizeFiles: IOException in getConfigFileNames");
            logger.fine(ex.toString());
        } finally {
            try {
                if (in != null)
                    in.close();
            } catch (IOException cex) {
            }
        }
        return files;
    }

    /**
     * Sync an individual file.  Return true if the file changed.
     */
    private boolean syncFile(File base, ModTime mt,
                            Payload.Outbound outboundPayload) {
        File f = new File(base, mt.name);
        if (mt.time != 0 && f.lastModified() == mt.time)
            return false;     // success, nothing to do
        if (logger.isLoggable(Level.FINEST))
            logger.finest("SynchronizeFiles: file " + mt.name +
                            " out of date, time " + f.lastModified());
        try {
            outboundPayload.attachFile("application/octet-stream",
                domainRootUri.relativize(f.toURI()),
                "configChange", f);
        } catch (IOException ioex) {
            logger.fine("SynchronizeFiles: IOException attaching file: " + f);
            logger.fine(ioex.toString());
        }
        return true;
    }

    /**
     * Send a request to the client to remove the specified file.
     */
    private void removeFile(File base, ModTime mt,
                            Payload.Outbound outboundPayload) {
        File f = new File(base, mt.name);
        if (logger.isLoggable(Level.FINEST))
            logger.finest("SynchronizeFiles: file " + mt.name +
                            " removed from client");
        try {
            outboundPayload.requestFileRemoval(
                domainRootUri.relativize(f.toURI()),
                "configChange", null);
        } catch (IOException ioex) {
            logger.fine("SynchronizeFiles: IOException removing file: " + f);
            logger.fine(ioex.toString());
        }
    }

    /**
     * Synchronize all the applications in the applications directory.
     * We use the mod time of the application directory to decide if
     * the application has changed.  If it has changed, we also send
     * any of the generated content.
     */
    private void synchronizeApplications(AdminCommandContext context,
                                    Server server, SyncRequest sr) {
        logger.finer("SynchronizeFiles: synchronize application instance " +
                                                                sr.instance);
        Map<String, Application> apps = getApps(server);
        Payload.Outbound outboundPayload = context.getOutboundPayload();
        File appsDir = env.getApplicationRepositoryPath();

        for (ModTime mt : sr.files) {
            if (apps.containsKey(mt.name)) {
                syncApp(apps.get(mt.name), appsDir, mt, outboundPayload);
                // if client has app, remove it from set
                apps.remove(mt.name);
            } else
                removeApp(apps.get(mt.name), appsDir, mt, outboundPayload);
        }

        // now do all the remaining apps the client doesn't have
        for (Map.Entry<String, Application> e : apps.entrySet())
            syncApp(e.getValue(), appsDir, new ModTime(e.getKey(), 0),
                                                        outboundPayload);
    }

    /**
     * Get the applications that should be
     * available to the specified server instance.
     */
    private Map<String, Application> getApps(Server server) {
        if (allApps)
            return getAllApps();

        Map<String, Application> apps = new HashMap<String, Application>();
        if (applications == null)
            return apps;        // no apps

        Cluster cluster = server.getCluster();
        List<ApplicationRef> appRefs;
        if (cluster != null)
            appRefs = cluster.getApplicationRef();
        else
            appRefs = server.getApplicationRef();
        for (ApplicationRef ref : appRefs)
            apps.put(ref.getRef(), applications.getApplication(ref.getRef()));
        return apps;
    }

    private Map<String, Application> getAllApps() {
        Map<String, Application> apps = new HashMap<String, Application>();
        if (applications == null)
            return apps;        // no apps

        for (ApplicationName module : applications.getModules()) {
            logger.finest("SynchronizeFiles: found module " + module.getName());
            if (module instanceof Application) {
                final Application app = (Application)module;
                if (app.getObjectType().equals("user")) {
                    logger.finest("SynchronizeFiles: got app " + app.getName());
                    if (Boolean.parseBoolean(app.getDirectoryDeployed()))
                        logger.finest("SynchronizeFiles: skipping directory " +
                                        "deployed app: " + app.getName());
                    else
                        apps.put(app.getName(), app);
                } else
                    logger.finest("SynchronizeFiles: found wrong app " +
                            app.getName() + ", type " + app.getObjectType());
            }
        }
        return apps;
    }

    /**
     * Synchronize the application named by mt.name in the
     * base directory.  If the application is out of date,
     * add the application files to the payload, including
     * the generated files.
     */
    private boolean syncApp(Application app, File base, ModTime mt,
                            Payload.Outbound outboundPayload) {
        logger.finer("SynchronizeFiles: sync app " + mt.name);
        try {
            File appDir = new File(base, mt.name);
            if (syncArchive) {
                File archive = app.application();
                logger.finest("SynchronizeFiles: check archive " + archive);
                if (mt.time != 0 && archive.lastModified() == mt.time)
                    return false;     // success, nothing to do

                // attach the archive file
                attachFile(archive, outboundPayload);
                /*
                 * Note that we don't need the deployment plan because
                 * we're not going to actually deploy it on the server
                 * instance, we're just going to unzip it.
                 */
            } else {
                logger.finest("SynchronizeFiles: check app dir " + appDir);
                if (mt.time != 0 && appDir.lastModified() == mt.time)
                    return false;     // success, nothing to do

                /*
                 * Recursively attach the application directory and
                 * all the generated directories.  The client will
                 * remove the old versions before installing the new ones.
                 */
                attachDir(appDir, outboundPayload);
            }

            // in either case, we attach the generated artifacts
            File gdir;
            gdir = env.getApplicationCompileJspPath();
            attachDir(new File(gdir, mt.name), outboundPayload);
            gdir = env.getApplicationGeneratedXMLPath();
            attachDir(new File(gdir, mt.name), outboundPayload);
            gdir = env.getApplicationEJBStubPath();
            attachDir(new File(gdir, mt.name), outboundPayload);
            gdir = new File(env.getApplicationStubPath(), "policy");
            attachDir(new File(gdir, mt.name), outboundPayload);
        } catch (IOException ioex) {
            logger.fine("SynchronizeFiles: IOException syncing app " + mt.name);
            logger.fine(ioex.toString());
        }
        return true;
    }

    /**
     * Attach the file to the payload.
     * If the file is a directory, recurse.  XXX - still need to support?
     */
    private void attachFile(File file, Payload.Outbound outboundPayload)
                                throws IOException {
        if (logger.isLoggable(Level.FINER)) {
            logger.finer("SynchronizeFiles: domainRootUri " + domainRootUri);
            logger.finer("SynchronizeFiles: file.toURI() " + file.toURI());
            logger.finer("SynchronizeFiles: attach file " +
                            domainRootUri.relativize(file.toURI()));
        }
        if (file.isDirectory()) {
            outboundPayload.requestFileReplacement("application/octet-stream",
                domainRootUri.relativize(file.toURI()),
                "configChange", null, file, true);
        } else {
            outboundPayload.attachFile("application/octet-stream",
                domainRootUri.relativize(file.toURI()),
                "configChange", file);
        }
    }

    /**
     * Attach the directory and all its contents to the payload.
     */
    private void attachDir(File file, Payload.Outbound outboundPayload)
                                throws IOException {
        if (logger.isLoggable(Level.FINER))
            logger.finer("SynchronizeFiles: attach directory " +
                            domainRootUri.relativize(file.toURI()));
        outboundPayload.requestFileReplacement("application/octet-stream",
            domainRootUri.relativize(file.toURI()),
            "configChange", null, file, true);
    }

    /**
     * Send requests to the client to remove the specified app directory
     * and all the generated directories.
     */
    private void removeApp(Application app, File base, ModTime mt,
                            Payload.Outbound outboundPayload) {
        logger.finer("SynchronizeFiles: remove app " + mt.name);
        try {
            File dir = new File(base, mt.name);
            removeDir(dir, outboundPayload);
            dir = env.getApplicationCompileJspPath();
            removeDir(new File(dir, mt.name), outboundPayload);
            dir = env.getApplicationGeneratedXMLPath();
            removeDir(new File(dir, mt.name), outboundPayload);
            dir = env.getApplicationEJBStubPath();
            removeDir(new File(dir, mt.name), outboundPayload);
            dir = new File(env.getApplicationStubPath(), "policy");
            removeDir(new File(dir, mt.name), outboundPayload);
        } catch (IOException ioex) {
            logger.fine("SynchronizeFiles: IOException removing app " +
                                                                    mt.name);
            logger.fine(ioex.toString());
        }
    }

    /**
     * Request recursive removal of teh specified directory.
     */
    private void removeDir(File file, Payload.Outbound outboundPayload)
                                throws IOException {
        outboundPayload.requestFileRemoval(
            domainRootUri.relativize(file.toURI()),
            "configChange", null, true);    // recursive removal
    }
}
