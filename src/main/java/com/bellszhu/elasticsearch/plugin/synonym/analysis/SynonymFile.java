/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.lucene.analysis.synonym.SynonymMap;

import java.io.Reader;

/**
 * @author bellszhu
 *
 */
public interface SynonymFile {
	
	public SynonymMap reloadSynonymMap();

	public boolean isNeedReloadSynonymMap();
	
	public Reader getReader();

}