/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, 2013, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.was.wlp_managed_8_5;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.test.api.Testable;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * WLPManagedContainer
 *
 * @author <a href="mailto:gerhard.poul@gmail.com">Gerhard Poul</a>
 * @version $Revision: $
 */
public class WLPManagedContainer implements DeployableContainer<WLPManagedContainerConfiguration>
{
   private static final String className = WLPManagedContainer.class.getName();

   private static Logger log = Logger.getLogger(className);

   private WLPManagedContainerConfiguration containerConfiguration;
   private URI arquillianServletUrl;

   public void setup(WLPManagedContainerConfiguration configuration)
   {
      if (log.isLoggable(Level.FINER)) {
            log.entering(className, "setup");
      }

      this.containerConfiguration = configuration;

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "setup");
      }
   }

   public void start() throws LifecycleException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "start");
      }

      if (!containerConfiguration.isContainerAlreadyRunning()) {

          ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c",
                  "./bin/server start " + containerConfiguration.getServerName() );
          pb.directory(new File(containerConfiguration.getWlpHome()));
          pb.redirectErrorStream(true);
          Process wlpProcess = null;
          try {
              wlpProcess = pb.start();
              InputStream is = wlpProcess.getInputStream();
              while (is.read() != -1) { }

              wlpProcess.waitFor();
          } catch (IOException e) {
              throw new RuntimeException(e);
          } catch (InterruptedException e) {
              throw new RuntimeException(e);
          }
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "start");
      }
   }

   public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "deploy");

         log.finer("Archive provided to deploy method: " + archive.toString(true));
      }

      String archiveName = archive.getName();
      String archiveType = createDeploymentType(archiveName);
      String deployName = createDeploymentName(archiveName);

      try {
         // If the deployment is to server.xml, then update server.xml with the application information
         if (containerConfiguration.isDeployTypeXML()) {
            // Throw error if deployment type is not ear, war, or eba
            if (!archiveType.equalsIgnoreCase("ear") && !archiveType.equalsIgnoreCase("war") && !archiveType.equalsIgnoreCase("eba"))
               throw new DeploymentException("Invalid archive type: " + archiveType + ".  Valid archive types are ear, war, and eba.");

            // Save the archive to disk so it can be loaded by the container.
            String appDir = getAppDirectory();
            File exportedArchiveLocation = new File(appDir, archiveName);
            archive.as(ZipExporter.class).exportTo(exportedArchiveLocation, true);

            // Read server.xml file into Memory
            Document document = readServerXML();

            // Add the archive as appropriate to the server.xml file
            addApplication(document, deployName, archiveName, archiveType);

            // Update server.xml on file system
            writeServerXML(document);
         }
         // Otherwise put the application in the dropins directory
         else {
            // Save the archive to disk so it can be loaded by the container.
            String dropInDir = getDropInDirectory();
            File exportedArchiveLocation = new File(dropInDir, archiveName);
            archive.as(ZipExporter.class).exportTo(exportedArchiveLocation, true);
         }

         // Wait until the application is deployed and available

          // Return metadata on how to contact the deployed application
          ProtocolMetaData metaData = new ProtocolMetaData();
          HTTPContext httpContext = new HTTPContext("localhost", containerConfiguration.getHttpPort());
          List<String> contextRoots = new ArrayList<String>();
          if (archive instanceof EnterpriseArchive) {
              contextRoots = findArquillianContextRoots((EnterpriseArchive)archive, deployName);
          } else {
              contextRoots.add(deployName);
          }
          // register ArquillianServletRunner
          for(String contextRoot : contextRoots) {
              Servlet servlet = new Servlet("ArquillianServletRunner", contextRoot);
              httpContext.add(servlet);
              arquillianServletUrl = servlet.getFullURI();
          }

          waitForDeploymentUrl(arquillianServletUrl, true);

          metaData.addContext(httpContext);

         if (log.isLoggable(Level.FINER)) {
            log.exiting(className, "deploy");
         }

         return metaData;
      } catch (Exception e) {
         throw new DeploymentException("Exception while deploying application.", e);
      }
   }

   private List<String> findArquillianContextRoots(final EnterpriseArchive ear, String deployName) throws DeploymentException {
	   List<String> contextRoots = new ArrayList<String>();
	   int testableWarCounter = 0;
	   int totalWarCounter = 0;
	   WebArchive latestWar = null;
	   for (ArchivePath path : ear.getContent().keySet()) {
		   if (path.get().endsWith("war")) {
			   WebArchive war = ear.getAsType(WebArchive.class, path);
			   totalWarCounter++;
			   if (Testable.isArchiveToTest(war)) {
				   contextRoots.add(getContextRoot(ear, war));
				   testableWarCounter++;
			   }
			   latestWar = war;
		   }
	   }
	   if(testableWarCounter == 0) {
		   if(totalWarCounter == 1) { // fallback only one war
			   contextRoots.add(getContextRoot(ear, latestWar));
		   } else { // default fallback
			   contextRoots.add(deployName);
		   }
	   }
	   return contextRoots;
	}

   private String getContextRoot(EnterpriseArchive ear, WebArchive war) throws DeploymentException {
	   org.jboss.shrinkwrap.api.Node applicationXmlNode = ear.get("META-INF/application.xml");
	   if(applicationXmlNode != null && applicationXmlNode.getAsset() != null) {
		   InputStream input = null;
		   try {
			   input = ear.get("META-INF/application.xml").getAsset().openStream();
			   Document applicationXml = readXML(input);
			   XPath xPath = XPathFactory.newInstance().newXPath();
			   XPathExpression ctxRootSelector = xPath.compile("//module/web[web-uri/text()='"+ war.getName() +"']/context-root");
			   String ctxRoot = ctxRootSelector.evaluate(applicationXml);
			   if(ctxRoot != null && ctxRoot.trim().length() > 0) {
				   return ctxRoot;
			   }
		   } catch (Exception e) {
			   throw new DeploymentException("Unable to retrieve context-root from application.xml");
		   } finally {
			   closeQuietly(input);
		   }
	   }
	   return createDeploymentName(war.getName());
	}

	private static void closeQuietly(Closeable closable) {
		try {
			if (closable != null)
				closable.close();
		} catch (IOException e) {
			log.log(Level.WARNING, "Exception while closing Closeable", e);
		}
	}
   public void undeploy(final Archive<?> archive) throws DeploymentException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "undeploy");
      }

      String archiveName = archive.getName();

      try {
         // If deploy type is xml, then remove the application from the xml file, which causes undeploy
         if (containerConfiguration.isDeployTypeXML()) {
            // Read the server.xml file into Memory
            Document document = readServerXML();

            // Remove the archive from the server.xml file
            removeApplication(document);

            // Update server.xml on file system
            writeServerXML(document);

            // Wait until the application is undeployed
            waitForDeploymentUrl(arquillianServletUrl, false);
            // Remove archive from the apps directory
            String appDir = getAppDirectory();
            File exportedArchiveLocation = new File(appDir, archiveName);
            if (!containerConfiguration.isFailSafeUndeployment()) {
            	try {
            		if(!Files.deleteIfExists(exportedArchiveLocation.toPath())) {
            			throw new DeploymentException("Archive already deleted from apps directory");
            		}
            	} catch (IOException e) {
            		throw new DeploymentException("Unable to delete archive from apps directory", e);
            	}
            } else {
            	try {
            		Files.deleteIfExists(exportedArchiveLocation.toPath());
            	} catch (IOException e) {
            		log.log(Level.WARNING, "Unable to delete archive from apps directory -> failsafe -> file marked for delete on exit", e);
            		exportedArchiveLocation.deleteOnExit();
            	}
            }
         }
         else {
            // Remove archive from the dropIn directory, which causes undeploy
            String dropInDir = getDropInDirectory();
            File exportedArchiveLocation = new File(dropInDir, archiveName);
            if (!exportedArchiveLocation.delete())
               throw new DeploymentException("Unable to delete archive from dropIn directory");

            // Wait until the application is undeployed
            waitForDeploymentUrl(arquillianServletUrl, false);
         }

      } catch (Exception e) {
          throw new DeploymentException("Exception while undeploying application.", e);
      }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "undeploy");
      }
   }

   private String getDropInDirectory() {
      String dropInDir = containerConfiguration.getWlpHome() + "/usr/servers/" +
            containerConfiguration.getServerName() + "/dropins";
      if (log.isLoggable(Level.FINER))
         log.finer("dropInDir: " + dropInDir);
      return dropInDir;
   }

   private String getAppDirectory()
   {
      String appDir = containerConfiguration.getWlpHome() + "/usr/servers/" +
         containerConfiguration.getServerName() + "/apps";
      if (log.isLoggable(Level.FINER))
         log.finer("appDir: " + appDir);
      return appDir;
   }

   private String getServerXML()
   {
      String serverXML = containerConfiguration.getWlpHome() + "/usr/servers/" +
         containerConfiguration.getServerName() + "/server.xml";
      if (log.isLoggable(Level.FINER))
         log.finer("server.xml: " + serverXML);
      return serverXML;
   }

   private String createDeploymentName(String archiveName)
   {
      return archiveName.substring(0, archiveName.lastIndexOf("."));
   }

   private String createDeploymentType(String archiveName)
   {
      return archiveName.substring(archiveName.lastIndexOf(".")+1);
   }

   private Document readServerXML() throws DeploymentException {
       return readServerXML(getServerXML());
   }

   private Document readServerXML(String serverXML) throws DeploymentException {
	   InputStream input = null;
	   try {
		   input = new FileInputStream(new File(serverXML));
		   return readXML(input);
	   } catch (Exception e) {
		   throw new DeploymentException("Exception while reading server.xml file.", e);
	   } finally {
	       closeQuietly(input);
	   }
	}

   private Document readXML(InputStream input) throws ParserConfigurationException, SAXException, IOException {
	   DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
	   DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
	   return documentBuilder.parse(input);
   }

   private void writeServerXML(Document doc) throws DeploymentException {
       writeServerXML(doc, getServerXML());
   }

   private void writeServerXML(Document doc, String serverXML) throws DeploymentException {
      try {
         TransformerFactory tf = TransformerFactory.newInstance();
         Transformer tr = tf.newTransformer();
         tr.setOutputProperty(OutputKeys.INDENT, "yes");
         DOMSource source = new DOMSource(doc);
         StreamResult res = new StreamResult(new File(serverXML));
         tr.transform(source, res);
      } catch (Exception e) {
         throw new DeploymentException("Exception wile writing server.xml file.", e);
      }
   }

   private Element createApplication(Document doc, String deploymentName, String archiveName, String type) throws DeploymentException
   {
      // create new Application
      Element application = doc.createElement("application");
      application.setAttribute("id", deploymentName);
      application.setAttribute("location", archiveName);
      application.setAttribute("name", deploymentName);
      application.setAttribute("type", type);

      // create shared library
      if (containerConfiguration.getSharedLib() != null
          ||containerConfiguration.getApiTypeVisibility() != null) {

         Element classloader = doc.createElement("classloader");

         if (containerConfiguration.getSharedLib() != null) {
            classloader.setAttribute("commonLibraryRef", containerConfiguration.getSharedLib());
         }
   
         if (containerConfiguration.getApiTypeVisibility() != null) {
            classloader.setAttribute("apiTypeVisibility", containerConfiguration.getApiTypeVisibility());
         }
         application.appendChild(classloader);
      }

      if(containerConfiguration.getSecurityConfiguration() != null) {
         InputStream input = null;
         try {
            input = new FileInputStream(new File(containerConfiguration.getSecurityConfiguration()));
            Document securityConfiguration = readXML(input);
            application.appendChild(doc.adoptNode(securityConfiguration.getDocumentElement().cloneNode(true)));
         } catch (Exception e) {
            throw new DeploymentException("Exception while reading " + containerConfiguration.getSecurityConfiguration() + " file.", e);
         } finally {
            closeQuietly(input);
         }
      }

      return application;
   }

   private void addApplication(Document doc, String deployName, String archiveName, String type) throws DOMException, DeploymentException
   {
      NodeList rootList = doc.getElementsByTagName("server");
      Node root = rootList.item(0);
      root.appendChild(createApplication(doc, deployName, archiveName, type));
   }

   private void removeApplication(Document doc)
   {
      Node server = doc.getElementsByTagName("server").item(0);
      NodeList serverlist = server.getChildNodes();
      for (int i=0; serverlist.getLength() > i; i++) {
         Node node = serverlist.item(i);
         if (node.getNodeName().equals("application")) {
            node.getParentNode().removeChild(node);
         }
      }
   }

   private void waitForDeploymentUrl(URI uri, boolean isDeploymentAvailable) throws DeploymentException, InterruptedException {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "waitForMBeanTargetState");
      }

      log.info("Waiting on Arquillian uri: " + uri);

      int unexpectedStatusCode = isDeploymentAvailable ? 404 : 200;
      String expectedServletHeader = isDeploymentAvailable ? "Servlet" : null;
      int timeout = isDeploymentAvailable ? containerConfiguration.getAppDeployTimeout() : containerConfiguration.getAppUndeployTimeout();
      boolean success = false;
      int retries = 40;
      do {
          int status = unexpectedStatusCode;
          String servletHeader = "";
          HttpURLConnection connection = null;
          try {
              connection = (HttpURLConnection) uri.toURL().openConnection();
              connection.setConnectTimeout(timeout * 1000);
              connection.setReadTimeout(timeout * 1000);
              connection.connect();
              status = connection.getResponseCode();
              servletHeader = connection.getHeaderField("X-Powered-By");
          } catch (IOException e) {}
          finally {
              connection.disconnect();
          }

          if ((status != unexpectedStatusCode) ||
                  ((servletHeader != null && servletHeader.contains(expectedServletHeader)) ||
                  servletHeader == null && expectedServletHeader == servletHeader)) {
              success = true;
          }
          else {
              Thread.sleep(250);
          }
            retries--;
      } while(!success || retries == 0);

       Thread.sleep(250);

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "waitForMBeanTargetState");
      }
   }

   public void stop() throws LifecycleException
   {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "stop");
      }

       if (!containerConfiguration.isContainerAlreadyRunning()) {

           ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c",
                   "./bin/server stop " + containerConfiguration.getServerName() );
           pb.directory(new File(containerConfiguration.getWlpHome()));
           pb.redirectErrorStream(true);

           Process wlpProcess = null;
           try {
               wlpProcess = pb.start();
               InputStream is = wlpProcess.getInputStream();
               while (is.read() != -1) { }

               wlpProcess.waitFor();
           } catch (IOException e) {
               throw new RuntimeException(e);
           } catch (InterruptedException e) {
               throw new RuntimeException(e);
           }
       }

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "stop");
      }
   }

   public ProtocolDescription getDefaultProtocol() {
      if (log.isLoggable(Level.FINER)) {
         log.entering(className, "getDefaultProtocol");
      }

      String defaultProtocol = "Servlet 3.0";

      if (log.isLoggable(Level.FINER)) {
         log.exiting(className, "getDefaultProtocol", defaultProtocol);
      }

      return new ProtocolDescription(defaultProtocol);
   }

   @Override
   public Class<WLPManagedContainerConfiguration> getConfigurationClass() {
      return WLPManagedContainerConfiguration.class;
   }

   public void deploy(Descriptor descriptor) throws DeploymentException {
      // TODO Auto-generated method stub

   }

   public void undeploy(Descriptor descriptor) throws DeploymentException {
      // TODO Auto-generated method stub

   }
}
