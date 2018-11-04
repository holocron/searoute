/**
 * 
 */
package eu.ec.eurostat.searoute;

import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.graph.build.feature.FeatureGraphGenerator;
import org.geotools.graph.build.line.LineStringGraphGenerator;
import org.geotools.graph.path.DijkstraShortestPathFinder;
import org.geotools.graph.path.Path;
import org.geotools.graph.structure.Edge;
import org.geotools.graph.structure.Graph;
import org.geotools.graph.structure.Node;
import org.geotools.graph.structure.basic.BasicEdge;
import org.geotools.graph.traverse.standard.DijkstraIterator;
import org.geotools.graph.traverse.standard.DijkstraIterator.EdgeWeighter;
import org.opengis.feature.simple.SimpleFeature;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.operation.linemerge.LineMerger;

/**
 * @author julien Gaffuri
 *
 */
public class SeaRouting {

	private Graph g;
	private EdgeWeighter weighter;

	public SeaRouting() throws MalformedURLException { this("WebContent/resources/shp/marnet.shp"); }
	public SeaRouting(String shpPath) throws MalformedURLException { this(new File(shpPath)); }
	public SeaRouting(File marnetFile) throws MalformedURLException { this(marnetFile.toURI().toURL()); }
	public SeaRouting(URL marnetFileURL) {
		try {
			Map<String, Serializable> map = new HashMap<>();
			map.put( "url", marnetFileURL );
			DataStore store = DataStoreFinder.getDataStore(map);
			FeatureCollection fc =  store.getFeatureSource(store.getTypeNames()[0]).getFeatures();

			//build graph
			FeatureIterator<?> it = fc.features();
			FeatureGraphGenerator gGen = new FeatureGraphGenerator(new LineStringGraphGenerator());
			while(it.hasNext()) gGen.add(it.next());
			g = gGen.getGraph();
			it.close();
			store.dispose();
		} catch (Exception e) { e.printStackTrace(); }

		//link nodes around the globe
		for(Object o : g.getNodes()){
			Node n = (Node)o;
			Coordinate c = ((Point)n.getObject()).getCoordinate();
			if(c.x==180) {
				Node n_ = getNode(new Coordinate(-c.x,c.y));
				//System.out.println(c + " -> " + ((Point)n_.getObject()).getCoordinate());
				BasicEdge be = new BasicEdge(n, n_);
				n.getEdges().add(be);
				n_.getEdges().add(be);
				g.getEdges().add(be);
			}
		}

		//define weighter
		weighter = new DijkstraIterator.EdgeWeighter() {
			public double getWeight(Edge e) {
				//edge around the globe
				if( e.getObject()==null ) return 0;

				SimpleFeature f = (SimpleFeature) e.getObject();
				MultiLineString line = (MultiLineString) f.getDefaultGeometry();
				//return line.getLength();
				return Utils.getLengthGeo(line);
			}
		};

	}



	private Path getShortestPath(Graph g, Node sN, Node dN, EdgeWeighter weighter){
		DijkstraShortestPathFinder pf = new DijkstraShortestPathFinder(g, sN, weighter);
		pf.calculate();
		return pf.getPath(dN);
	}

	//lon,lat
	public Coordinate getPosition(Node n){
		if(n==null) return null;
		Point pt = (Point)n.getObject();
		if(pt==null) return null;
		return pt.getCoordinate();
	}

	//get closest node from a position (lon,lat)
	public Node getNode(Coordinate c){
		double dMin = Double.MAX_VALUE;
		Node nMin=null;
		for(Object o : g.getNodes()){
			Node n = (Node)o;
			double d = getPosition(n).distance(c); //TODO fix that !
			//double d=Utils.getDistance(getPosition(n), c);
			if(d==0) return n;
			if(d<dMin) {dMin=d; nMin=n;}
		}
		return nMin;
	}

	//return the route geometry from origin/destination coordinates
	public MultiLineString getRoute(double oLon, double oLat, double dLon, double dLat) {
		return getRoute(new Coordinate(oLon,oLat), new Coordinate(dLon,dLat));
	}
	public MultiLineString getRoute(Coordinate oPos, Coordinate dPos) {
		return getRoute(oPos, getNode(oPos), dPos, getNode(dPos));
	}
	//get the route when the node are known
	public MultiLineString getRoute(Coordinate oPos, Node oN, Coordinate dPos, Node dN) {

		//get node positions
		Coordinate oNPos = getPosition(oN), dNPos = getPosition(dN);

		//test if route should be based on network
		//route do not need network if straight line between two points is smaller than the total distance to reach the network
		double dist = -1;
		dist = Utils.getDistance(oPos, dPos);
		double distN = -1;
		distN = Utils.getDistance(oPos, oNPos) + Utils.getDistance(dPos, dNPos);

		if(dist>=0 && distN>=0 && distN > dist){
			//return direct route
			GeometryFactory gf = new GeometryFactory();
			return gf.createMultiLineString(new LineString[]{ gf.createLineString(new Coordinate[]{oPos,dPos}) });
		}

		//Compute dijkstra from start node
		Path path = null;
		synchronized (g) {
			path = getShortestPath(g, oN,dN,weighter);
		}


		if(path == null) return null;

		//build line geometry
		LineMerger lm = new LineMerger();
		for(Object o : path.getEdges()){
			Edge e = (Edge)o;
			SimpleFeature f = (SimpleFeature) e.getObject();
			if(f==null) continue;
			MultiLineString mls = (MultiLineString) f.getDefaultGeometry();
			lm.add(mls);
		}
		//add first and last parts
		GeometryFactory gf = new GeometryFactory();
		lm.add(gf.createLineString(new Coordinate[]{oPos,oNPos}));
		lm.add(gf.createLineString(new Coordinate[]{dPos,dNPos}));

		Collection<?> lss = lm.getMergedLineStrings();
		return gf.createMultiLineString( lss.toArray(new LineString[lss.size()]) );
	}

	public static void main(String[] args) throws MalformedURLException {
		SeaRouting sr = new SeaRouting();
		//get from origin () to destination ()
		MultiLineString geom = sr.getRoute(5.3, 43.3, 121.8, 31.2);
		System.out.println(geom);
		double dist = Utils.getLengthGeo(geom);
		System.out.println(dist);
		String gj = Utils.toGeoJSON(geom);
		System.out.println(gj);
	}

}
