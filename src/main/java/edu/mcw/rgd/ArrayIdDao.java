package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AliasDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.XdbId;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * @since 10/10/12
 * <p>
 * all traffic to/from database must go through this class
 */
public class ArrayIdDao {

    Logger logInserted = Logger.getLogger("insertedAffyIds");
    Logger logRetired = Logger.getLogger("retiredAffyIds");

    AliasDAO adao = new AliasDAO();
    XdbIdDAO xdao = new XdbIdDAO();

    public String getConnectionInfo() {
        return adao.getConnectionInfo();
    }

    /**
     * return external ids for any combination of parameters given in filter;
     * if given parameter is null or 0, it means, that any value of this parameter could be accepted
     *
     * @param filter - any combination of acc_id,xdb_id,rgd_id and src_pipeline is honored
     * @param speciesType - species type key
     * @param objectKey - object key
     * @return list of external ids matching the filter; empty list is returned if no matching entries are found
     * @throws Exception when unexpected error in spring framework occurs
     */
    public List<XdbId> getXdbIds(XdbId filter, int speciesType, int objectKey) throws Exception {

        return xdao.getXdbIds(filter, speciesType, objectKey);
    }

    /**
     * get active genes with given NCBI GeneId -- exclude splices and alleles
     * @param accId - gene id to be looked for
     * @return list of Gene objects
     */
    synchronized public List<Gene> getActiveGenesByNcbiGeneId(String accId) throws Exception {

        // check if genes are in cache
        List<Gene> genes = _cacheGenes.get(accId);
        if( genes==null ) {
            // not in cache
            genes = xdao.getActiveGenesByXdbId(XdbId.XDB_KEY_NCBI_GENE, accId);

            // exclude splices and alleles
            Iterator<Gene> it = genes.iterator();
            while( it.hasNext() ) {
                Gene gene = it.next();
                if( gene.isVariant() ) {
                    it.remove();
                }
            }
            _cacheGenes.put(accId, genes);
        }
        return genes;
    }

    // cache of active genes
    Map<String, List<Gene>> _cacheGenes = new HashMap<>(20003);

    /**
     * retired aliases get their type changed to old_array_id_affy xxx
     * @param aliases list of Alias objects
     * @throws Exception if something wrong happens in spring framework
     */
    public void retireAliases(List<Alias> aliases) throws Exception{

        Set<String> aliasTypesNonArray = new HashSet<>(adao.getAliasTypesExcludingArrayIds());
        for( Alias alias: aliases ) {
            // change the alias type by adding prefix 'old_'
            alias.setTypeName( "old_"+alias.getTypeName() );
            // ensure the type name is on alias types
            if( !aliasTypesNonArray.contains(alias.getTypeName()) ) {
                insertAliasType(alias.getTypeName());
                aliasTypesNonArray.add(alias.getTypeName());
            }
            adao.updateAlias(alias);
            logRetired.info(SpeciesType.getCommonName(alias.getSpeciesTypeKey())+" RGD:"+alias.getRgdId()
                    +" "+alias.getTypeName()+" "+alias.getValue());
        }
    }

    /**
     * insert alias list into ALIASES table
     * @param aliases list of aliases to be inserted
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int insertAliases(List<Alias> aliases) throws Exception {

        for( Alias alias: aliases ) {
            logInserted.info(SpeciesType.getCommonName(alias.getSpeciesTypeKey())+" RGD:"+alias.getRgdId()
                    +" "+alias.getTypeName()+" "+alias.getValue());
        }
        return adao.insertAliases(aliases);
    }

    /**
     * get list of aliases of given type
     * @param aliasType alias type
     * @return list of aliases of given type
     * @throws Exception if something wrong happens in spring framework
     */
    public List<Alias> getAliasesByType(String aliasType) throws Exception {
        return adao.getAliasesByType(aliasType);
    }

    public List<String> getAffyAliasTypes() throws Exception {
        List<String> aliasTypes = adao.getAliasTypes();
        Iterator<String> it = aliasTypes.iterator();
        while( it.hasNext() ) {
            String aliasType = it.next();
            if( !aliasType.startsWith("array_id_affy_") )
                it.remove();
        }
        return aliasTypes;
    }

    public int insertAliasType(String aliasType) throws Exception {
        return adao.insertAliasType(aliasType, "created by ArrayIdImporter pipeline at "+new Date());
    }
}
