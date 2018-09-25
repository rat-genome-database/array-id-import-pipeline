package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.*;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import synergizer.SynergizerClient;

import java.util.*;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: jdepons
 * Date: 4/26/12
 * Time: 12:55 PM
 * Pipeline job to import array ids from http://llama.mshri.on.ca/synergizer/translate/
 */
public class ArrayIdImporter {

    static Log log = LogFactory.getLog("core");

    ArrayIdDao dao = new ArrayIdDao();
    private String version;

    // list of NCBI gene ids mapping to multiple rgd ids
    private Map<String, String> multis = new HashMap<>();
    // list of affy alias types in RGD
    private Set<String> affyAliasTypes;
    private Map<String,String> taxonNcbiToEnsemble;

    public static void main(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        ArrayIdImporter importer = (ArrayIdImporter) (bf.getBean("importer"));
        log.info(importer.getVersion());
        long time1 = System.currentTimeMillis();

        importer.run();

        long time2 = System.currentTimeMillis();
        log.info("array import complete: "+Utils.formatElapsedTime(time1, time2));
    }

    public void run() throws Exception {

        affyAliasTypes = new HashSet<>(dao.getAffyAliasTypes());

        for( int speciesTypeKey: SpeciesType.getSpeciesTypeKeys() ) {
            if( speciesTypeKey>0 ) {
                exec(speciesTypeKey);
            }
        }

        // dump multis
        if( !multis.isEmpty() ) {
            log.warn("CONFLICT: affy ids not imported for "+multis.size()+" multis");
            log.warn("CONFLICT: (multi: one NCBI gene id mapped to multiple active gene rgd ids)");
            for( Map.Entry<String,String> entry: multis.entrySet() ) {
                log.warn(" MULTI> GeneId:"+entry.getKey()+", RgdIds:"+entry.getValue());
            }
        }
    }

    void exec( int speciesTypeKey ) throws Exception {

        // sometimes Ensembl (and synergizer) uses different taxon name than NCBI
        // for those cases, we convert from NCBI to Ensembl taxon names
        String taxonomicName = SpeciesType.getTaxonomicName(speciesTypeKey);
        String ensemblTaxonName = getTaxonNcbiToEnsemble().get(taxonomicName);
        if( ensemblTaxonName!=null ) {
            taxonomicName = ensemblTaxonName;
        }

        long time1 = System.currentTimeMillis();
        System.out.println("\nStarting import for "+taxonomicName);

        SynergizerClient client = new synergizer.SynergizerClient();
        Set<String> arrayIds = client.availableDomains("ensembl", taxonomicName);
        if( arrayIds.isEmpty() ) {
            System.out.println("  no array ids for "+taxonomicName);
        }
        else {
            Set<String> source_ids = getSourceIds(speciesTypeKey);

            for (String arrayId : arrayIds) {
                if (!arrayId.startsWith("affy_"))
                    continue;

                String aliasType = "array_id_" + arrayId + "_ensembl";
                if (!affyAliasTypes.contains(aliasType)) {
                    System.out.println("INSERTING ALIAS TYPE " + aliasType);
                    dao.insertAliasType(aliasType);
                    affyAliasTypes.add(aliasType);
                }

                exec("ensembl", taxonomicName, speciesTypeKey, "entrezgene", arrayId, source_ids);
            }
        }

        long time2 = System.currentTimeMillis();
        System.out.println("Finished import for "+taxonomicName+"; "+Utils.formatElapsedTime(time1, time2));
    }

    Set<String> getSourceIds(int speciesTypeKey) throws Exception {

        XdbId xid = new XdbId();
        xid.setXdbKey(XdbId.XDB_KEY_NCBI_GENE);

        List<XdbId> xList = dao.getXdbIds(xid, speciesTypeKey, RgdId.OBJECT_KEY_GENES);

        Set<String> source_ids = new java.util.HashSet<>();

        for (XdbId rec: xList) {
            source_ids.add(rec.getAccId());
        }
        return source_ids;
    }

    void exec(String authority, String species, int speciesTypeKey, String from, String to, Set<String> source_ids ) throws Exception {

        SynergizerClient client = new synergizer.SynergizerClient();
        SynergizerClient.TranslateResult res = client.translate(authority, species, from, to, source_ids);

        if (res.translationMap().size() < 1) {
            log.warn("Did not find any array IDS to import for " + to);
            return;
        }


        String aliasTypeName= "array_id_" + to + "_ensembl";

        List<Alias> aliasesInRgd = dao.getAliasesByType(aliasTypeName);
        String msg = aliasTypeName+": in_RGD="+aliasesInRgd.size();

        Set<Alias> aliasesIncoming = getIncomingAliases(speciesTypeKey, aliasTypeName, res.translationMap().entrySet());
        msg += ", incoming="+aliasesIncoming.size();

        Set<Alias> aliasesForDelete = new HashSet<>(aliasesInRgd);
        Set<Alias> aliasesForInsert = new HashSet<>(aliasesIncoming);
        aliasesForInsert.removeAll(aliasesForDelete); // INCOMING SUBTRACT IN-RGD
        if( !aliasesForInsert.isEmpty() ) {
            dao.insertAliases(new ArrayList<>(aliasesForInsert));
            msg += ", inserted="+aliasesForInsert.size();
        }

        aliasesForDelete.removeAll(aliasesIncoming); // IN-RGD SUBTRACT INCOMING
        if( !aliasesForDelete.isEmpty() ) {
            dao.deleteAliases(new ArrayList<>(aliasesForDelete));
            msg += ", deleted="+aliasesForDelete.size();
        }
        log.info(msg);
    }

    Set<Alias> getIncomingAliases(int speciesTypeKey, String aliasTypeName, Set<Map.Entry<String,Set<String>>> ncbiSet) throws Exception {

        Set<Alias> aliasesIncoming = new HashSet<>(2003);

        for( Map.Entry<String,Set<String>> entry: ncbiSet ) {

            List<Gene> rgdIds = dao.getActiveGenesByNcbiGeneId(entry.getKey());
            if (rgdIds.isEmpty()) {
                continue;
            }

            if (rgdIds.size() > 1) {
                String ids = Utils.concatenate(",", rgdIds, "getRgdId");
                multis.put(entry.getKey(), ids);
                continue;
            }

            Gene g = rgdIds.get(0);

            Set<String> s = entry.getValue();
            if (s != null) {
                for (String value : s) {
                    Alias a = new Alias();
                    a.setRgdId(g.getRgdId());
                    a.setSpeciesTypeKey(speciesTypeKey);
                    a.setNotes("ArrayIdImport pipeline");
                    a.setTypeName(aliasTypeName);
                    a.setValue(value);
                    aliasesIncoming.add(a);
                }
            }
        }

        return aliasesIncoming;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setTaxonNcbiToEnsemble(Map<String, String> taxonNcbiToEnsemble) {
        this.taxonNcbiToEnsemble = taxonNcbiToEnsemble;
    }

    public Map<String, String> getTaxonNcbiToEnsemble() {
        return taxonNcbiToEnsemble;
    }
}

