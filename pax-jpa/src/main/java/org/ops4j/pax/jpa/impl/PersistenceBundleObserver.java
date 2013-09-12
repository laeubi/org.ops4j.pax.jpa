/*
 * Copyright 2012 Harald Wellmann.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.jpa.impl;

import static org.ops4j.pax.jpa.JpaConstants.JPA_DRIVER;
import static org.ops4j.pax.jpa.JpaConstants.JPA_MANIFEST_HEADER;
import static org.ops4j.pax.jpa.JpaConstants.JPA_PASSWORD;
import static org.ops4j.pax.jpa.JpaConstants.JPA_PERSISTENCE_XML;
import static org.ops4j.pax.jpa.JpaConstants.JPA_PROVIDER;
import static org.ops4j.pax.jpa.JpaConstants.JPA_URL;
import static org.ops4j.pax.jpa.JpaConstants.JPA_USER;
import static org.osgi.service.jdbc.DataSourceFactory.OSGI_JDBC_DRIVER_CLASS;
import static org.osgi.service.jpa.EntityManagerFactoryBuilder.JPA_UNIT_NAME;
import static org.osgi.service.jpa.EntityManagerFactoryBuilder.JPA_UNIT_PROVIDER;
import static org.osgi.service.jpa.EntityManagerFactoryBuilder.JPA_UNIT_VERSION;

import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceProvider;
import javax.xml.bind.JAXBException;

import org.ops4j.pax.jpa.impl.descriptor.PersistenceDescriptorParser;
import org.ops4j.pax.jpa.impl.descriptor.PersistenceUnitInfoImpl;
import org.ops4j.pax.jpa.jaxb.Persistence;
import org.ops4j.pax.jpa.jaxb.Persistence.PersistenceUnit;
import org.ops4j.pax.swissbox.extender.BundleManifestScanner;
import org.ops4j.pax.swissbox.extender.BundleObserver;
import org.ops4j.pax.swissbox.extender.BundleWatcher;
import org.ops4j.pax.swissbox.extender.ManifestEntry;
import org.ops4j.pax.swissbox.extender.RegexKeyManifestFilter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.jpa.EntityManagerFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistenceBundleObserver implements BundleObserver<ManifestEntry> {

    public static final String BUNDLE_NAME = "org.ops4j.pax.jpa";

    private static Logger log = LoggerFactory.getLogger(PersistenceBundleObserver.class);

    private PersistenceDescriptorParser parser = new PersistenceDescriptorParser();

    private BundleWatcher<ManifestEntry> watcher;

    private Map<String, PersistenceUnitInfoImpl> persistenceUnits = new HashMap<String, PersistenceUnitInfoImpl>();
    private List<ServiceReference<PersistenceProvider>> persistenceProviders = new ArrayList<ServiceReference<PersistenceProvider>>();
    private List<ServiceReference<DataSourceFactory>> dataSourceFactories = new ArrayList<ServiceReference<DataSourceFactory>>();

    public PersistenceBundleObserver() {
        log.debug("instantiating observer");
    }

    @SuppressWarnings("unchecked")
    public void activate(BundleContext bc) {
        log.debug("starting bundle {}", BUNDLE_NAME);

        RegexKeyManifestFilter manifestFilter = new RegexKeyManifestFilter(
            JPA_MANIFEST_HEADER);
        BundleManifestScanner scanner = new BundleManifestScanner(manifestFilter);
        watcher = new BundleWatcher<ManifestEntry>(bc, scanner, this);
        watcher.start();
    }

    public void deactivate(BundleContext bc) {
        log.debug("stopping bundle {}", BUNDLE_NAME);
        watcher.stop();
    }

    public synchronized void addPersistenceProvider(
        ServiceReference<PersistenceProvider> persistenceProvider) {
        log.debug("adding persistence provider {}",
            persistenceProvider.getProperty(JPA_PROVIDER));
        persistenceProviders.add(persistenceProvider);

        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getProvider() == null && canAssign(puInfo)) {
                assignPersistenceUnit(puInfo);
                if (canComplete(puInfo)) {
                        activatePersistenceUnit(puInfo);
                }
            }
        }
    }


    private boolean canAssign(PersistenceUnitInfoImpl puInfo) {
        BundleContext bc = puInfo.getBundle().getBundleContext();
        PersistenceProvider provider = null;
        
        String providerClassName = puInfo.getPersistenceProviderClassName();
        if (providerClassName == null) {
            if (!persistenceProviders.isEmpty()) {
                provider = bc.getService(persistenceProviders.get(0));
                puInfo.setProvider(provider);
                return true;
            }
        }
        else {
            for (ServiceReference<PersistenceProvider> providerRef : persistenceProviders) {
                if (providerClassName.equals(providerRef.getProperty(JPA_PROVIDER))) {
                    provider = bc.getService(providerRef);
                    puInfo.setProvider(provider);
                    return true;
                }            
            }
            
        }
        puInfo.setProvider(null);
        return false;
    }

    private boolean canComplete(PersistenceUnitInfoImpl puInfo) {
        puInfo.setDataSourceFactory(null);
        BundleContext bc = puInfo.getBundle().getBundleContext();
        String driver = puInfo.getProperties().getProperty(JPA_DRIVER);
        if (driver == null) {
            return false;
        }
        
        DataSourceFactory dsf = null;
        for (ServiceReference<DataSourceFactory> dsfRef : dataSourceFactories) {
            if (driver.equals(dsfRef.getProperty(OSGI_JDBC_DRIVER_CLASS))) {
                dsf = bc.getService(dsfRef);
                puInfo.setDataSourceFactory(dsf);
                return true;
            }            
        }
        
        return false;
    }

    private void assignPersistenceUnit(PersistenceUnitInfoImpl puInfo) {
        PersistenceProvider provider = puInfo.getProvider();
        Bundle bundle = puInfo.getBundle();

        EntityManagerFactoryBuilder builder = new EntityManagerFactoryBuilderImpl(puInfo);
        Dictionary<String, String> emfBuilderServiceProps = new Hashtable<String, String>();
        emfBuilderServiceProps.put(JPA_UNIT_NAME, puInfo.getPersistenceUnitName());
        emfBuilderServiceProps.put(JPA_UNIT_VERSION, bundle.getVersion().toString());
        emfBuilderServiceProps.put(JPA_UNIT_PROVIDER, provider.getClass().getName());
        ServiceRegistration<EntityManagerFactoryBuilder> builderReg = bundle.getBundleContext().registerService(
            EntityManagerFactoryBuilder.class, builder, emfBuilderServiceProps);
        puInfo.setEmfBuilderRegistration(builderReg);
        puInfo.setState(PersistenceUnitState.READY);
    }

    private void activatePersistenceUnit(PersistenceUnitInfoImpl puInfo) {
        PersistenceProvider provider = puInfo.getProvider();
        Bundle bundle = puInfo.getBundle();
        Properties emfProps = (Properties) puInfo.getProperties().clone();
        emfProps.remove(JPA_DRIVER);
        emfProps.remove(JPA_URL);
        emfProps.remove(JPA_PASSWORD);
        emfProps.remove(JPA_USER);

        EntityManagerFactory emf = provider.createContainerEntityManagerFactory(puInfo, emfProps);

        Dictionary<String, String> emfServiceProps = new Hashtable<String, String>();
        emfServiceProps.put(JPA_UNIT_NAME, puInfo.getPersistenceUnitName());
        emfServiceProps.put(JPA_UNIT_VERSION, bundle.getVersion().toString());
        emfServiceProps.put(JPA_UNIT_PROVIDER, provider.getClass().getName());
        ServiceRegistration<EntityManagerFactory> reg = bundle.getBundleContext().registerService(
            EntityManagerFactory.class, emf, emfServiceProps);
        puInfo.setEmfRegistration(reg);
        puInfo.setState(PersistenceUnitState.COMPLETE);
    }

    private void deactivatePersistenceUnit(PersistenceUnitInfoImpl puInfo) {
        puInfo.unregister();
        puInfo.setEmfRegistration(null);
        puInfo.setDataSourceFactory(null);
        //puInfo.setProvider(null);
    }

    public synchronized void removePersistenceProvider(
        ServiceReference<PersistenceProvider> persistenceProvider) {
        log.debug("removing persistence provider {}",
            persistenceProvider.getProperty(JPA_PROVIDER));
        persistenceProviders.remove(persistenceProvider);

        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getState() == PersistenceUnitState.COMPLETE && !canAssign(puInfo)) {
                deactivatePersistenceUnit(puInfo);
            }
        }
    }

    public synchronized void addDataSourceFactory(ServiceReference<DataSourceFactory> dsf) {
        log.debug("adding data source factory {}",
            dsf.getProperty(OSGI_JDBC_DRIVER_CLASS));
        dataSourceFactories.add(dsf);

        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getDataSourceFactory() == null && canComplete(puInfo)) {
                if (puInfo.getProvider() != null) {
                    activatePersistenceUnit(puInfo);
                }
            }
        }
    }

    public synchronized void removeDataSourceFactory(ServiceReference<DataSourceFactory> dsf) {
        log.debug("removing data source factory {}",
            dsf.getProperty(OSGI_JDBC_DRIVER_CLASS));
        dataSourceFactories.remove(dsf);
        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getState() == PersistenceUnitState.COMPLETE && !canComplete(puInfo)) {
                deactivatePersistenceUnit(puInfo);
            }
        }
    }

    @Override
    public synchronized void addingEntries(Bundle bundle, List<ManifestEntry> entries) {
        log.info("discovered persistence bundle {}_{}", bundle.getSymbolicName(),
            bundle.getVersion());
        ManifestEntry entry = entries.get(0);
        List<URL> resources = parseMetaPersistenceHeader(bundle, entry.getValue());
        for (URL resource : resources) {
            processPersistenceDescriptor(bundle, resource);
        }

        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getProvider() == null && canAssign(puInfo)) {
                assignPersistenceUnit(puInfo);
            }
            if (puInfo.getDataSourceFactory() == null && canComplete(puInfo)) {
                if (puInfo.getProvider() != null) {
                    activatePersistenceUnit(puInfo);
                }
            }
        }
    }

    private List<URL> parseMetaPersistenceHeader(Bundle bundle, String value) {
        URL defaultUrl = bundle.getEntry(JPA_PERSISTENCE_XML);
        boolean defaultUrlFound = false;
        List<URL> urls = new ArrayList<URL>();
        String[] parts = value.split(",\\s*");
        for (String part : parts) {
            String resource = part.trim();
            if (!resource.isEmpty()) {
                URL url = bundle.getEntry(resource);
                if (url != null) {
                    urls.add(url);
                    if (url.equals(defaultUrl)) {
                        defaultUrlFound = true;
                    }
                }
            }
        }
        if (defaultUrl != null && !defaultUrlFound) {
            urls.add(0, defaultUrl);
        }
        return urls;
    }

    private void processPersistenceDescriptor(Bundle bundle, URL persistenceXml) {
        try {
            Persistence descriptor = parser.parseDescriptor(persistenceXml);
            for (PersistenceUnit persistenceUnit : descriptor.getPersistenceUnit()) {
                processPersistenceUnit(bundle, persistenceUnit);
            }
        }
        catch (JAXBException exc) {
            log.error("cannot parse persistence descriptor", exc);
        }
    }

    private void processPersistenceUnit(Bundle bundle, PersistenceUnit persistenceUnit) {
        String puName = persistenceUnit.getName();
        PersistenceUnitInfoImpl puInfo = persistenceUnits.get(puName);
        if (puInfo != null) {
            log.error("ignoring persistence unit [{}] from bundle [{}], "
                + "unit with this name already registered by bundle [{}]", new Object[] { puName,
                bundle, puInfo.getBundle() });
            return;
        }
        
        log.info("processing persistence unit {}", puName);
        Properties puProps = parser.parseProperties(persistenceUnit);
        puInfo = new PersistenceUnitInfoImpl(bundle, persistenceUnit, puProps);
        persistenceUnits.put(puInfo.getPersistenceUnitName(), puInfo);
    }

    @Override
    public synchronized void removingEntries(Bundle bundle, List<ManifestEntry> entries) {
        log.info("removed persistence bundle {} {}", bundle.getSymbolicName(), bundle.getVersion());
        
        for (PersistenceUnitInfoImpl puInfo : persistenceUnits.values()) {
            if (puInfo.getBundle().equals(bundle)) {
                persistenceUnits.remove(puInfo.getPersistenceUnitName());
            }
        }
    }
}
