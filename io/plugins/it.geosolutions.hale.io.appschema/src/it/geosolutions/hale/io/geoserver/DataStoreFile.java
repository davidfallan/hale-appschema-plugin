/*
 * Copyright (c) 2015 Data Harmonisation Panel
 * 
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Data Harmonisation Panel <http://www.dhpanel.eu>
 */

package it.geosolutions.hale.io.geoserver;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.entity.ContentType;

/**
 * Class representing a datastore file resource.
 * 
 * @author Stefano Costa, GeoSolutions
 */
public class DataStoreFile extends AbstractResource {

	/**
	 * "Workspace" attribute.
	 */
	public static final String WORKSPACE = "ws";
	/**
	 * "Datastore" attribute.
	 */
	public static final String DATASTORE = "ds";
	/**
	 * "Extension" attribute.
	 */
	public static final String EXTENSION = "extension";

	private static final Set<String> allowedAttributes = new HashSet<String>();

	static {
		allowedAttributes.add(WORKSPACE);
		allowedAttributes.add(DATASTORE);
		allowedAttributes.add(EXTENSION);
	}

	private final InputStream resourceStream;
	private final ContentType contentType;

	/**
	 * Constructor.
	 * 
	 * @param resourceStream the input stream providing the file contents
	 */
	public DataStoreFile(InputStream resourceStream) {
		this.resourceStream = resourceStream;
		this.contentType = DEF_CONTENT_TYPE;
	}

	/**
	 * Two-args constructor.
	 * 
	 * @param resourceStream the input stream providing the file contents
	 * @param contentType the content type
	 */
	public DataStoreFile(InputStream resourceStream, ContentType contentType) {
		this.resourceStream = resourceStream;
		this.contentType = contentType;
	}

	/**
	 * @see it.geosolutions.hale.io.geoserver.AbstractResource#contentType()
	 */
	@Override
	public ContentType contentType() {
		return contentType;
	}

	/**
	 * @see it.geosolutions.hale.io.geoserver.Resource#name()
	 */
	@Override
	public String name() {
		return resourceStream.toString();
	}

	/**
	 * @see it.geosolutions.hale.io.geoserver.AbstractResource#resourceStream()
	 */
	@Override
	protected InputStream resourceStream() {
		return resourceStream;
	}

	/**
	 * @see it.geosolutions.hale.io.geoserver.AbstractResource#allowedAttributes()
	 */
	@Override
	protected Set<String> allowedAttributes() {
		return allowedAttributes;
	}

	/**
	 * Enumeration listing the supported datastore file types.
	 * 
	 * @author Stefano Costa, GeoSolutions
	 */
	public static enum Extension {

		/**
		 * Shapefile
		 */
		shp,
		/**
		 * Properties file
		 */
		properties,
		/**
		 * H2 database
		 */
		h2,
		/**
		 * SpatiaLite database
		 */
		spatialite,
		/**
		 * App-schema mapping file
		 */
		appschema

	}

}
