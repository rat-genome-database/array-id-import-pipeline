package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AliasDAO;
import edu.mcw.rgd.datamodel.Alias;
import edu.mcw.rgd.datamodel.RgdId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class Debug {

    public static void main(String[] args) throws Exception {

        AliasDAO dao = new AliasDAO();

        Set<String> aliasTypes = new HashSet<>(dao.getAliasTypes());

        Connection conn = dao.getDataSource().getConnection();
        String query = "SELECT * FROM rgd_query WHERE data_type LIKE 'array id%'";
        PreparedStatement ps = conn.prepareStatement(query);
        ResultSet rs = ps.executeQuery();
        while( rs.next() ) {
            int queryKey = rs.getInt("query_key");
            int rgdId = rs.getInt("rgd_id");
            String dataType = rs.getString("data_type");
            String keywordLc = rs.getString("keyword_lc");

            Alias alias = new Alias();
            alias.setRgdId(rgdId);
            alias.setTypeName("old_"+dataType.replace(" ", "_"));
            alias.setValue(keywordLc);
            alias.setNotes("from RGD_QUERY table");

            // ensure the alias type is in the database
            if( !aliasTypes.contains(alias.getTypeName())) {
                dao.insertAliasType(alias.getTypeName(), null);
                aliasTypes.add(alias.getTypeName());
            }
            dao.insertAlias(alias);

            dao.update("DELETE FROM rgd_query WHERE query_key=?", queryKey);
        }
        conn.close();
    }

    void cleanupRgdQuery() throws Exception {

        String query = "SELECT query_key FROM rgd_query WHERE rgd_id=? and data_obj='aliases' and data_type=? and keyword_lc=?";


        AliasDAO dao = new AliasDAO();
        int totalNoMatch = 0;
        int totalDeleted = 0;

        List<Integer> sp = new ArrayList<>();
        sp.add(1);
        sp.add(2);
        sp.add(3);
        sp.add(6);
        List<Alias> aliases = new ArrayList<>();
        for( int spe: sp ) {
            List<Alias> ali = dao.getActiveArrayIdAliasesFromEnsembl(RgdId.OBJECT_KEY_GENES, spe);
            System.out.println("  "+spe+" array_id aliases: "+ali.size());
            aliases.addAll(ali);
        }
        while( !aliases.isEmpty() ) {
            int noMatch = 0;
            int deleted = 0;
            Collections.shuffle(aliases);
            Iterator<Alias> it = aliases.iterator();
            while (it.hasNext()) {
                Alias a = it.next();
                int rgdId = a.getRgdId();
                String dataType = a.getTypeName().replace("_", " ");
                String keywordLc = a.getValue().toLowerCase();
                String queryKeyS = dao.getStringResult(query, rgdId, dataType, keywordLc);
                if (queryKeyS == null) {
                    noMatch++;
                    totalNoMatch++;
                } else {
                    int queryKey = Integer.parseInt(queryKeyS);
                    dao.update("DELETE FROM rgd_query WHERE query_key=?", queryKey);
                    deleted++;
                    totalDeleted++;
                }
                it.remove();

                if( (noMatch+deleted)%20000 == 0 ) {
                    System.out.println(aliases.size()+"  noMatch " + noMatch+", deleted " + deleted);
                    System.out.println(aliases.size()+"  totalNoMatch " + totalNoMatch+", totalDeleted " + totalDeleted);
                    System.out.println();
                    break;
                }
            }
        }
        System.out.println(aliases.size()+"  totalNoMatch " + totalNoMatch+", totalDeleted " + totalDeleted);
    }
}