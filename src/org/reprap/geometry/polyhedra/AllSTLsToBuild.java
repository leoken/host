package org.reprap.geometry.polyhedra;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.ArrayList;
import org.reprap.geometry.LayerRules;
import org.reprap.geometry.polygons.BooleanGrid;
import org.reprap.geometry.polygons.BooleanGridList;
import org.reprap.geometry.polygons.CSG2D;
import org.reprap.geometry.polygons.HalfPlane;
import org.reprap.geometry.polygons.Interval;
import org.reprap.geometry.polygons.Point2D;
import org.reprap.geometry.polygons.Polygon;
import org.reprap.geometry.polygons.PolygonList;
import org.reprap.geometry.polygons.Rectangle;
import org.reprap.Attributes;
import org.reprap.Extruder;
import org.reprap.Preferences;
import org.reprap.RFO;
import org.reprap.utilities.Debug;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.GeometryArray;
import javax.media.j3d.Group;
import javax.media.j3d.SceneGraphObject;
import javax.media.j3d.Shape3D;
import javax.media.j3d.Transform3D;
import javax.vecmath.Matrix4d;
import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;

/**
 * This class holds a list of STLObjects that represents everything that is to be built.
 * 
 * An STLObject may consist of items from several STL files, possible of different materials.
 * But they are all tied together relative to each other in space.
 * 
 * @author Adrian
 *
 */


public class AllSTLsToBuild 
{	
	/**
	 * Class to hold infill patterns
	 * @author ensab
	 *
	 */
	class InFillPatterns
	{
		BooleanGridList bridges;
		BooleanGridList insides;
		BooleanGridList surfaces;	
		PolygonList hatchedPolygons;
		
		InFillPatterns()
		{
			bridges = new BooleanGridList();
			insides = new BooleanGridList();
			surfaces = new BooleanGridList();
			hatchedPolygons = new PolygonList();
		}
		
		InFillPatterns(InFillPatterns ifp)
		{
			bridges = ifp.bridges;
			insides = ifp.insides;
			surfaces = ifp.surfaces;
			hatchedPolygons = ifp.hatchedPolygons;
		}
	}
	
	/**
	 * 3D bounding box
	 * @author ensab
	 *
	 */
	class BoundingBox
	{
		private Rectangle XYbox;
		private Interval Zint;
		
		public BoundingBox(Point3d p0)
		{	
			Zint = new Interval(p0.z, p0.z);
			XYbox = new Rectangle(new Interval(p0.x, p0.x), new Interval(p0.y, p0.y));
		}
		
		public BoundingBox(BoundingBox b)
		{
			Zint = new Interval(b.Zint);
			XYbox = new Rectangle(b.XYbox);
		}
		
		public void expand(Point3d p0)
		{
			Zint.expand(p0.z);
			XYbox.expand(new Point2D(p0.x, p0.y));
		}
		
		public void expand(BoundingBox b)
		{
			Zint.expand(b.Zint);
			XYbox.expand(b.XYbox);
		}
	}
	
	/**
	 * Line segment consisting of two points.
	 * @author Adrian
	 *
	 */
	class LineSegment
	{	
		/**
		 * The ends of the line segment
		 */
		public Point2D a = null, b = null;
		
		/**
		 * The attribute (i.e. RepRap material) of the segment.
		 */
		public Attributes att = null;

//		protected void finalize() throws Throwable
//		{
//			a = null;
//			b = null;
//			att = null;
//			super.finalize();
//		}
		
		/**
		 * Constructor takes two intersection points with an STL triangle edge.
		 * @param p
		 * @param q
		 */
		public LineSegment(Point2D p, Point2D q, Attributes at)
		{
			if(at == null)
				Debug.e("LineSegment(): null attributes!");
			a = p;
			b = q;
			att = at;
		}
	}
	
	/**
	 * Ring buffer cache to hold previously computed slices for doing 
	 * infill and support material calculations.
	 * @author ensab
	 *
	 */
	class SliceCache
	{
		private BooleanGridList[][] sliceRing;
		private BooleanGridList[][] supportRing;
		private int[] layerNumber;
		private int ringPointer;
		private final int noLayer = Integer.MIN_VALUE;
		private int ringSize = 10;
		
		public SliceCache(LayerRules lr)
		{
			if(lr == null)
				Debug.e("SliceCache(): null LayerRules!");
			ringSize = lr.sliceCacheSize();
			sliceRing = new BooleanGridList[ringSize][stls.size()];
			supportRing = new BooleanGridList[ringSize][stls.size()];
			layerNumber = new int[ringSize];
			ringPointer = 0;
			for(int layer = 0; layer < ringSize; layer++)
				for(int stl = 0; stl < stls.size(); stl++)
				{
					sliceRing[layer][stl] = null;
					supportRing[layer][stl] = null;
					layerNumber[layer] = noLayer;
				}
		}
		
		private int getTheRingLocationForWrite(int layer)
		{
			for(int i = 0; i < ringSize; i++)
				if(layerNumber[i] == layer)
					return i;

			int rp = ringPointer;
			for(int s = 0; s < stls.size(); s++)
			{
				sliceRing[rp][s] = null;
				supportRing[rp][s] = null;
			}
			ringPointer++;
			if(ringPointer >= ringSize)
				ringPointer = 0;
			return rp;
		}
		
		public void setSlice(BooleanGridList slice, int layer, int stl)
		{
			int rp = getTheRingLocationForWrite(layer);
			layerNumber[rp] = layer;
			sliceRing[rp][stl] = slice;
		}
		
		public void setSupport(BooleanGridList support, int layer, int stl)
		{
			int rp = getTheRingLocationForWrite(layer);
			layerNumber[rp] = layer;
			supportRing[rp][stl] = support;
		}
		
		private int getTheRingLocationForRead(int layer)
		{
			int rp = ringPointer;
			for(int i = 0; i < ringSize; i++)
			{
				rp--;
				if(rp < 0)
					rp = ringSize - 1;
				if(layerNumber[rp] == layer)
					return rp;
			}
			return -1;
		}
		
		public BooleanGridList getSlice(int layer, int stl)
		{
			int rp = getTheRingLocationForRead(layer);
			if(rp >= 0)
				return sliceRing[rp][stl];
			return null;
		}
		
		public BooleanGridList getSupport(int layer, int stl)
		{
			int rp = getTheRingLocationForRead(layer);
			if(rp >= 0)
				return supportRing[rp][stl];
			return null;
		}
	}
	
	/**
	 * OpenSCAD file extension
	 */
	private static final String scad = ".scad";
	
	/**
	 * The list of things to be built
	 */
	private List<STLObject> stls;
	
	/**
	 * The building layer rules
	 */
	private LayerRules layerRules = null;
	
	/**
	 * A plan box round each item
	 */
	private List<Rectangle> rectangles;
	
	/**
	 * New list of things to be built for reordering
	 */
	private List<STLObject> newstls;
	
	/**
	 * The XYZ box around everything
	 */
	//private RrRectangle XYbox;
	private BoundingBox XYZbox;
	
	/**
	 * The lowest and highest points
	 */
	private Interval Zrange;
	
	/**
	 * Is the list editable?
	 */
	private boolean frozen;
	
	/**
	 * Recently computed slices
	 */
	private SliceCache cache;
	
	/**
	 * Simple constructor
	 *
	 */
	public AllSTLsToBuild()
	{
		stls = new ArrayList<STLObject>();
		rectangles = null;
		newstls = null;
		XYZbox = null;
		Zrange = null;
		frozen = false;
		cache = null;
		layerRules = null;
	}
	
	/**
	 * Add a new STLObject
	 * @param s
	 */
	public void add(STLObject s)
	{
		if(frozen)
			Debug.e("AllSTLsToBuild.add(): adding an item to a frozen list.");
		stls.add(s);
	}
	
	/**
	 * Add a new collection
	 * @param s
	 */
	public void add(AllSTLsToBuild a)
	{
		if(frozen)
			Debug.e("AllSTLsToBuild.add(): adding a collection to a frozen list.");
		for(int i = 0; i < a.size(); i++)
			stls.add(a.get(i));
	}
	
	/**
	 * Get the i-th STLObject
	 * @param i
	 * @return
	 */
	public STLObject get(int i)
	{
		return stls.get(i);
	}
	
	/**
	 * Delete an object
	 * @param i
	 */
	public void remove(int i)
	{
		if(frozen)
			Debug.e("AllSTLsToBuild.remove(): removing an item from a frozen list.");
		stls.remove(i);
	}
	
	/**
	 * Find an object in the list
	 * @param st
	 * @return
	 */
	private int findSTL(STLObject st)
	{
		if(size() <= 0)
		{
			Debug.e("AllSTLsToBuild.findSTL(): no objects to pick from!");
			return -1;			
		}
		int index = -1;
		for(int i = 0; i < size(); i++)
			if(get(i) == st)
			{
				index = i;
				break;
			}
		if(index < 0)
		{
			Debug.e("AllSTLsToBuild.findSTL(): dud object submitted.");
			return -1;
		}
		return index;
	}
	
	/**
	 * Find an object in the list and return the next one.
	 * @param st
	 * @return
	 */
	public STLObject getNextOne(STLObject st)
	{
		int index = findSTL(st);
		index++;
		if(index >= size())
			index = 0;
		return get(index);
	}
	
	/**
	 * Return the number of objects.
	 * @return
	 */
	public int size()
	{
		return stls.size();
	}
	
	/**
	 * Create an OpenSCAD (http://www.openscad.org/) program that will read everything
	 * in in the same pattern as it is stored here.  It can then
	 * be written by OpenSCAD as a single STL.
	 * @return
	 */
	public String toSCAD()
	{
		String result = "union()\n{\n";
		for(int i = 0; i < size(); i++)
			result += get(i).toSCAD();
		result += "}";
		return result;
	}

	
	/**
	 * Write everything to an OpenSCAD program.
	 * @param fn the directory to write into
	 */
	public void saveSCAD(String fn)
	{
		if(fn.charAt(fn.length()-1) == File.separator.charAt(0))
			fn = fn.substring(0, fn.length()-1);
		int sepIndex = fn.lastIndexOf(File.separator);
		int fIndex = fn.indexOf("file:");
		String name = fn.substring(sepIndex + 1, fn.length());
		String path;
		if(sepIndex >= 0)
		{
			if(fIndex >= 0)
				path = fn.substring(fIndex + 5, sepIndex + 1);
			else
				path = fn.substring(0, sepIndex + 1);
		} else
			path = "";
		path += name + File.separator;
		name += scad;
		if(!RFO.checkFile(path, name))
			return;
		File file= new File(path);
		if(!file.exists())
			file.mkdir();
		RFO.copySTLs(this, path);
		try
		{
			PrintWriter out = new PrintWriter(new FileWriter(path+name));
			out.println(toSCAD());
			out.close();
		} catch (Exception e)
		{
			Debug.e("AllSTLsToBuild.saveSCAD(): can't open file: " + path+name);
		}
	}
	
	/**
	 * Reorder the list under user control.  The user sends items from the
	 * old list one by one.  These are added to a new list in that order.  
	 * When there's only one left that is added last automatically.
	 * 
	 * Needless to say, this process must be carried through to completion.
	 * The function returns true while the process is ongoing, false when
	 * it's complete.
	 * 
	 * @param st
	 * @return
	 */
	public boolean reorderAdd(STLObject st)
	{
		if(frozen)
			Debug.d("AllSTLsToBuild.reorderAdd(): attempting to reorder a frozen list.");
		
		if(newstls == null)
			newstls = new ArrayList<STLObject>();
		
		int index = findSTL(st);
		newstls.add(get(index));
		stls.remove(index);
		
		if(stls.size() > 1)
			return true;
		
		newstls.add(get(0));
		stls = newstls;
		newstls = null;
		cache = null;  // Just in case...
		
		return false;
	}
	
	/**
	 * Scan everything loaded and set up the bounding boxes
	 */
	public void setBoxes()
	{
		//if(XYZbox != null) // Already done?
		//	return;
		
		rectangles = new ArrayList<Rectangle>();
		for(int i = 0; i < stls.size(); i++)
			rectangles.add(null);	
		
		BoundingBox s;
		
		for(int i = 0; i < stls.size(); i++)
		{
			STLObject stl = stls.get(i);
			Transform3D trans = stl.getTransform();
			
			BranchGroup bg = stl.getSTL();
			
			java.util.Enumeration<?> enumKids = bg.getAllChildren();

			while(enumKids.hasMoreElements())
			{
				Object ob = enumKids.nextElement();

				if(ob instanceof BranchGroup)
				{
					BranchGroup bg1 = (BranchGroup)ob;
					Attributes att = (Attributes)(bg1.getUserData());
					if(XYZbox == null)
					{
						XYZbox = BBox(att.getPart(), trans);
						if(rectangles.get(i) == null)
							rectangles.set(i, new Rectangle(XYZbox.XYbox));
						else
							rectangles.set(i, Rectangle.union(rectangles.get(i), XYZbox.XYbox));
					} else
					{
						s = BBox(att.getPart(), trans);
						if(s != null)
						{
							XYZbox.expand(s);
							if(rectangles.get(i) == null)
								rectangles.set(i, new Rectangle(s.XYbox));
							else
								rectangles.set(i, Rectangle.union(rectangles.get(i), s.XYbox));
						}
					}
				}
			}
			if(rectangles.get(i) == null)
				Debug.e("AllSTLsToBuild:ObjectPlanRectangle(): object " + i + " is empty");
		}				
	}
	
	/**
	 * Freeze the list - no more editing.
	 * Also compute the XY box round everything.
	 * Also compute the individual plan boxes round each STLObject.
	 */
	private void freeze()
	{
		if(frozen)
			return;
		if(layerRules == null)
			Debug.e("AllSTLsToBuild.freeze(): layerRules not set!");
		frozen = true;
			
		if(cache == null)
			cache = new SliceCache(layerRules);
		setBoxes();
	}
	
	/**
	 * Run through a Shape3D and find its enclosing XYZ box
	 * @param shape
	 * @param trans
	 * @param z
	 */
	private BoundingBox BBoxPoints(Shape3D shape, Transform3D trans)
    {
		BoundingBox b = null;
        GeometryArray g = (GeometryArray)shape.getGeometry();
        Point3d p1 = new Point3d();
        Point3d q1 = new Point3d();
        
        if(g != null)
        {
            for(int i = 0; i < g.getVertexCount(); i++) 
            {
                g.getCoordinate(i, p1);
                trans.transform(p1, q1);
                if(b == null)
                	b = new BoundingBox(q1);
                else
                	b.expand(q1);
            }
        }
        return b;
    }
	
	/**
	 * Unpack the Shape3D(s) from value and find their exclosing XYZ box
	 * @param value
	 * @param trans
	 * @param z
	 */
	private BoundingBox BBox(Object value, Transform3D trans) 
    {
		BoundingBox b = null;
		BoundingBox s;
		
        if(value instanceof SceneGraphObject) 
        {
            SceneGraphObject sg = (SceneGraphObject)value;
            if(sg instanceof Group) 
            {
                Group g = (Group)sg;
                java.util.Enumeration<?> enumKids = g.getAllChildren( );
                while(enumKids.hasMoreElements())
                {
                	if(b == null)
                		b = BBox(enumKids.nextElement(), trans);
                	else
                	{
                		s = BBox(enumKids.nextElement(), trans);
                		if(s != null)
                			b.expand(s);
                	}
                }
            } else if (sg instanceof Shape3D) 
            {
                b = BBoxPoints((Shape3D)sg, trans);
            }
        }
        
        return b;
    }
	
	
	/**
	 * Return the XY box round everything
	 * @return
	 */
	public Rectangle ObjectPlanRectangle()
	{
		if(XYZbox == null)
			Debug.e("AllSTLsToBuild.ObjectPlanRectangle(): null XYZbox!");
		return XYZbox.XYbox;
	}
	
	/**
	 * Find the top of the highest object.
	 * Calling this freezes the list.
	 * @return
	 */
	public double maxZ()
	{
		if(XYZbox == null)
			Debug.e("AllSTLsToBuild.maxZ(): null XYZbox!");
		return XYZbox.Zint.high();
	}
	
	/**
	 * Stitch together the some of the edges to form a polygon.
	 * @param edges
	 * @return
	 */
	private Polygon getNextPolygon(ArrayList<LineSegment> edges)
	{
		if(!frozen)
		{
			Debug.e("AllSTLsToBuild:getNextPolygon() called for an unfrozen list!");
			freeze();
		}
		if(edges.size() <= 0)
			return null;
		LineSegment next = edges.get(0);
		edges.remove(0);
		Polygon result = new Polygon(next.att, true);
		result.add(next.a);
		result.add(next.b);
		Point2D start = next.a;
		Point2D end = next.b;
		
		boolean first = true;
		while(edges.size() > 0)
		{
			double d2 = Point2D.dSquared(start, end);
			if(first)
				d2 = Math.max(d2, 1);
			first = false;
			boolean aEnd = false;
			int index = -1;
			for(int i = 0; i < edges.size(); i++)
			{
				double dd = Point2D.dSquared(edges.get(i).a, end);
				if(dd < d2)
				{
					d2 = dd;
					aEnd = true;
					index = i;
				}
				dd = Point2D.dSquared(edges.get(i).b, end);
				if(dd < d2)
				{
					d2 = dd;
					aEnd = false;
					index = i;
				}
			}

			if(index >= 0)
			{
				next = edges.get(index);
				edges.remove(index);
				int ipt = result.size() - 1;
				if(aEnd)
				{
					result.set(ipt, Point2D.mul(Point2D.add(next.a, result.point(ipt)), 0.5));
					result.add(next.b);
					end = next.b;
				} else
				{
					result.set(ipt, Point2D.mul(Point2D.add(next.b, result.point(ipt)), 0.5));
					result.add(next.a);
					end = next.a;				
				}
			} else
				return result;
		}
		
		Debug.d("AllSTLsToBuild.getNextPolygon(): exhausted edge list!");
		
		return result;
	}
	
	/**
	 * Get all the polygons represented by the edges.
	 * @param edges
	 * @return
	 */
	private PolygonList simpleCull(ArrayList<LineSegment> edges)
	{
		if(!frozen)
		{
			Debug.e("AllSTLsToBuild:simpleCull() called for an unfrozen list!");
			freeze();
		}
		PolygonList result = new PolygonList();
		Polygon next = getNextPolygon(edges);
		while(next != null)
		{
			if(next.size() >= 3)
				result.add(next);
			next = getNextPolygon(edges);
		}
		
		return result;
	}
	
	/**
	 * Compute the support hatching polygons for this set of patterns
	 * @param stl
	 * @param layerConditions
	 * @return
	 */
	public PolygonList computeSupport(int stl)
	{
		// No more additions or movements, please
		
		freeze();
		
		// We start by computing the union of everything in this layer because
		// that is everywhere that support _isn't_ needed.
		// We give the union the attribute of the first thing found, though
		// clearly it will - in general - represent many different substances.
		// But it's only going to be subtracted from other shapes, so what it's made
		// from doesn't matter.
		
		int layer = layerRules.getModelLayer();
		
		BooleanGridList thisLayer = slice(stl, layer);
		
		BooleanGrid unionOfThisLayer;
		Attributes a;
		
		if(thisLayer.size() > 0)
		{
			unionOfThisLayer = thisLayer.get(0);
			a = unionOfThisLayer.attribute();
		}else
		{
			a = stls.get(stl).attributes(0);
			unionOfThisLayer = BooleanGrid.nullBooleanGrid();
		}
		for(int i = 1; i < thisLayer.size(); i++)
			unionOfThisLayer = BooleanGrid.union(unionOfThisLayer, thisLayer.get(i), a);
		
		// Expand the union of this layer a bit, so that any support is a little clear of 
		// this layer's boundaries.
		
		BooleanGridList allThis = new BooleanGridList();
		allThis.add(unionOfThisLayer);
		allThis = allThis.offset(layerRules, true, 2);  // 2mm gap is a bit of a hack...
		if(allThis.size() > 0)
			unionOfThisLayer = allThis.get(0);
		else
			unionOfThisLayer = BooleanGrid.nullBooleanGrid();

		// Get the layer above and union it with this layer.  That's what needs
		// support on the next layer down.
		
		BooleanGridList previousSupport = cache.getSupport(layer+1, stl);

		cache.setSupport(BooleanGridList.unions(previousSupport, thisLayer), layer, stl);
		
		// Now we subtract the union of this layer from all the stuff requiring support in the layer above.
		
		BooleanGridList support = new BooleanGridList();
	
		if(previousSupport != null)
		{
			for(int i = 0; i < previousSupport.size(); i++)
			{
				BooleanGrid above = previousSupport.get(i);
				a = above.attribute();
				Extruder e = a.getExtruder().getSupportExtruder();
				if(e != null)
				{
					if(layerRules.extruderActiveThisLayer(e.getID()))
						support.add(BooleanGrid.difference(above, unionOfThisLayer, a));
				}
			}
			support = support.unionDuplicates();
		}
		
		// Now force the attributes of the support pattern to be the support extruders
		// for all the materials in it.  If the material isn't active in this layer, remove it from the list
		
		for(int i = 0; i < support.size(); i++)
		{
			Extruder e = support.attribute(i).getExtruder().getSupportExtruder();
			if(e == null)
			{
				Debug.e("AllSTLsToBuild.computeSupport(): null support extruder specified!");
				continue;
			}
			support.get(i).forceAttribute(new Attributes(e.getMaterial(), null, null, e.getAppearance()));
		}
		
		// Finally compute the support hatch.
		
		PolygonList result = support.hatch(layerRules, false, null, true);
		
		return result;
	}
	
	/**
	 * This finds an individual land in landPattern
	 * @param landPattern
	 * @return
	 */
	private BooleanGrid findLand(BooleanGrid landPattern)
	{
		Point2D seed = landPattern.findSeed();
		if(seed == null)
			return null;
		
		return landPattern.floodCopy(seed);
	}
	
	/**
	 * This finds the bridges that cover cen.  It assumes that there is only one material at one point in space...
	 * @param unSupported
	 * @param cen1
	 * @return
	 */
	int findBridges(BooleanGridList unSupported, Point2D cen)
	{
		for(int i = 0; i < unSupported.size(); i++)
		{
			BooleanGrid bridges = unSupported.get(i);
			if(bridges.get(cen))
				return i;
		}
		return -1;
	}
	
//	private void nameAtt(BooleanGridList bgl, String name)
//	{
//		for(int i = 0; i < bgl.size(); i++)
//		{
//			Attributes a = bgl.get(i).attribute();
//			Debug.e(name + " has material: " + a.getMaterial());
//		}
//	}
	

	/**
	 * Compute the bridge infill for unsupported polygons for a slice.  This is very heuristic...
	 * 
	 * @param infill
	 * @param lands
	 * @param layerConditions
	 * @return
	 * 
	 */
	public InFillPatterns bridgeHatch(InFillPatterns infill, BooleanGridList lands, LayerRules layerConditions)
	{
		InFillPatterns result = new InFillPatterns(infill);
		BooleanGridList b;
		
		for(int i = 0; i < lands.size(); i++)
		{
			BooleanGrid landPattern = lands.get(i);
			BooleanGrid land1;
			
			// Find a land
			
			while((land1 = findLand(landPattern)) != null)
			{
				// Find the middle of the land
				
				Point2D cen1 = land1.findCentroid();
				
				// Wipe this land from the land pattern
				
				landPattern = BooleanGrid.difference(landPattern, land1);
				
				if(cen1 == null)
				{
					Debug.e("AllSTLsToBuild.bridges(): First land found with no centroid!");
					continue;
				}
				
				// Find the bridge that goes with the land
				
				int bridgesIndex = findBridges(result.bridges, cen1);
				if(bridgesIndex < 0)
				{
					Debug.d("AllSTLsToBuild.bridges(): Land found with no corresponding bridge.");
					continue;
				}
				BooleanGrid bridges = result.bridges.get(bridgesIndex);
				
				// The bridge must cover the land too
				
				BooleanGrid bridge = bridges.floodCopy(cen1);
				if(bridge == null)
					continue;
				
				// Find the other land (the first has been wiped)
				
				BooleanGrid land2 = null;

				land2 = BooleanGrid.intersection(bridge, landPattern);
				
				// Find the middle of this land
				
				Point2D cen2 = land2.findCentroid();
				if(cen2 == null)
				{
					Debug.d("AllSTLsToBuild.bridges(): Second land found with no centroid.");
					
					// No second land implies a ring of support - just infill it.
					
					result.hatchedPolygons.add(bridge.hatch(layerConditions.getHatchDirection(bridge.attribute().getExtruder(), false), 
							bridge.attribute().getExtruder().getExtrusionInfillWidth(), 
							bridge.attribute()));
					
					// Remove this bridge (in fact, just its lands) from the other infill patterns.
					
					b = new BooleanGridList();
					b.add(bridge);
					result.insides = BooleanGridList.differences(result.insides, b);
					result.surfaces = BooleanGridList.differences(result.surfaces, b);
				} else
				{
					// Wipe this land from the land pattern

					landPattern = BooleanGrid.difference(landPattern, land2);

					// (Roughly) what direction does the bridge go in?

					Point2D centroidDirection = Point2D.sub(cen2, cen1).norm();
					Point2D bridgeDirection = centroidDirection;

					// Fine the edge of the bridge that is nearest parallel to that, and use that as the fill direction

					double spMax = Double.NEGATIVE_INFINITY;
					double sp;
					PolygonList bridgeOutline = bridge.allPerimiters(bridge.attribute());
					for(int pol = 0; pol < bridgeOutline.size(); pol++)
					{
						Polygon polygon = bridgeOutline.polygon(i);

						for(int vertex1 = 0; vertex1 < polygon.size(); vertex1++)
						{
							int vertex2 = vertex1+1;
							if(vertex2 >= polygon.size()) // We know the polygon must be closed...
								vertex2 = 0;
							Point2D edge = Point2D.sub(polygon.point(vertex2), polygon.point(vertex1));

							if((sp = Math.abs(Point2D.mul(edge, centroidDirection))) > spMax)
							{
								spMax = sp;
								bridgeDirection = edge;
							}

						}
					}

					// Build the bridge

					result.hatchedPolygons.add(bridge.hatch(new HalfPlane(new Point2D(0,0), bridgeDirection), 
							bridge.attribute().getExtruder().getExtrusionInfillWidth(), 
							bridge.attribute()));
					
					// Remove this bridge (in fact, just its lands) from the other infill patterns.
					
					b = new BooleanGridList();
					b.add(bridge);
					result.insides = BooleanGridList.differences(result.insides, b);
					result.surfaces = BooleanGridList.differences(result.surfaces, b);
				}
				// remove the bridge from the bridge patterns.
				b = new BooleanGridList();
				b.add(bridge);
				result.bridges = BooleanGridList.differences(result.bridges, b);
			}
		}
		
		return result;
	}
	
	/**
	 * Set the building layer rules as soon as we know them
	 * @param lr
	 */
	public void setLayerRules(LayerRules lr)
	{
		layerRules = lr;
	}
	
	/**
	 * Select from a slice (allLayer) just those parts of it that will be plotted this layer
	 * @param allLayer
	 * @param infill
	 * @param support
	 * @return
	 */
	private BooleanGridList neededThisLayer(BooleanGridList allLayer, boolean infill, boolean support)
	{
		BooleanGridList neededSlice = new BooleanGridList();
		for(int i = 0; i < allLayer.size(); i++)
		{
			Extruder e;
			if(infill)
				e = allLayer.get(i).attribute().getExtruder().getInfillExtruder();
			else if(support)
				e = allLayer.get(i).attribute().getExtruder().getSupportExtruder();
			else
				e = allLayer.get(i).attribute().getExtruder();
			if(e != null)
				if(layerRules.extruderActiveThisLayer(e.getID()))
					neededSlice.add(allLayer.get(i));
		}
		return neededSlice;
	}
	
	/**
	 * Compute the infill hatching polygons for this set of patterns
	 * @param stl
	 * @param layerConditions
	 * @param startNearHere
	 * @return
	 */
	public PolygonList computeInfill(int stl) 
	{
		// Where the result will be stored.
		
		InFillPatterns infill = new InFillPatterns();
		
		// No more additions or movements, please
		
		freeze();
		
		// Where are we and what does the current slice look like?
		
		int layer = layerRules.getModelLayer();
		
		BooleanGridList slice = slice(stl, layer);
		
		
		int surfaceLayers = 1;
		for(int i = 0; i < slice.size(); i++)
		{
			Extruder e = slice.get(i).attribute().getExtruder();
			if(e.getSurfaceLayers() > surfaceLayers)
				surfaceLayers = e.getSurfaceLayers();
		}
		
		// Get the bottom out of the way - no fancy calculations needed.
		
		if(layer <= surfaceLayers)
		{
			slice = slice.offset(layerRules, false, -1);
			slice = neededThisLayer(slice, false, false);
			infill.hatchedPolygons = slice.hatch(layerRules, true, null, false);
			return infill.hatchedPolygons;
		}
		
		// If we are solid but the slices above or below us weren't, we need some fine infill as
		// we are (at least partly) surface.
		
		// The intersection of the slices above does not need surface infill...
		// How many do we need to consider?
		
				
		BooleanGridList above = slice(stl, layer+1);
		for(int i = 2; i <= surfaceLayers; i++)
			above = BooleanGridList.intersections(slice(stl, layer+i), above);
		
		// ...nor does the intersection of those below.
		
		BooleanGridList below = slice(stl, layer-1);
		for(int i = 2; i <= surfaceLayers; i++)
			below = BooleanGridList.intersections(slice(stl, layer-i), below);
	
		// The bit of the slice with nothing above it needs fine infill...
		
		BooleanGridList nothingabove = BooleanGridList.differences(slice, above);
		
		// ...as does the bit with nothing below.
		
		BooleanGridList nothingbelow = BooleanGridList.differences(slice, below);

		// Find the region that is not surface.
		
		infill.insides = BooleanGridList.differences(slice, nothingbelow);
		infill.insides = BooleanGridList.differences(infill.insides, nothingabove);
		
		// Parts with nothing under them that have no support material
		// need to have bridges constructed to do the best for in-air infill.
		
		infill.bridges = nothingbelow.cullNoSupport();
		
		// The remainder with nothing under them will be supported by support material
		// and so needs no special treatment.
		
		nothingbelow = nothingbelow.cullSupport();
		
		// All the parts of this slice that need surface infill
		
		infill.surfaces = BooleanGridList.unions(nothingbelow, nothingabove);
		
		// Make the bridges fatter, then crop them to the slice.
		// This will make them interpenetrate at their ends/sides to give
		// bridge landing areas.
		
		infill.bridges = infill.bridges.offset(layerRules, false, 2);
		infill.bridges = BooleanGridList.intersections(infill.bridges, slice);
		
		// Find the landing areas as a separate set of shapes that go with the bridges.
		
		BooleanGridList lands = BooleanGridList.intersections(infill.bridges, BooleanGridList.unions(infill.insides,infill.surfaces));
		
		// Shapes will be outlined, and so need to be shrunk to allow for that.  But they
		// must not also shrink from each other internally.  So initially expand them so they overlap
		
		infill.bridges = infill.bridges.offset(layerRules, false, 1);
		infill.insides = infill.insides.offset(layerRules, false, 1);
		infill.surfaces = infill.surfaces.offset(layerRules, false, 1);
		
		// Now intersect them with the slice so the outer edges are back where they should be.
		
		infill.bridges = BooleanGridList.intersections(infill.bridges, slice);
		infill.insides = BooleanGridList.intersections(infill.insides, slice);
		infill.surfaces = BooleanGridList.intersections(infill.surfaces, slice);
		
		// Now shrink them so the edges are in a bit to allow the outlines to
		// be put round the outside.  The inner joins should now shrink back to be
		// adjacent to each other as they should be.
		
		infill.bridges = infill.bridges.offset(layerRules, false, -1);
		infill.insides = infill.insides.offset(layerRules, false, -1);
		infill.surfaces = infill.surfaces.offset(layerRules, false, -1);
		
		// Generate the infill patterns.  We do the bridges first, as each bridge subtracts its
		// lands from the other two sets of shapes.  We want that, so they don't get infilled twice.
		
		infill = bridgeHatch(infill, lands, layerRules);
		infill.insides = neededThisLayer(infill.insides, true, false);
		infill.hatchedPolygons.add(infill.insides.hatch(layerRules, false, null, false));
		infill.surfaces = neededThisLayer(infill.surfaces, false, false);
		infill.hatchedPolygons.add(infill.surfaces.hatch(layerRules, true, null, false));
		
	
		return infill.hatchedPolygons;
	}
	
	/**
	 * Compute the polygon to lay down for the machine to wipe its nose on.
	 * @param a
	 * @return
	 */
	public Polygon shieldPolygon(Attributes a)
	{
		Rectangle rr = ObjectPlanRectangle();
		Point2D corner = Point2D.add(rr.sw(), new Point2D(-3, -3));
		Polygon ell = new Polygon(a, false);
		ell.add(corner);
		ell.add(Point2D.add(corner, new Point2D(0, 10)));
		ell.add(Point2D.add(corner, new Point2D(-2, 10)));
		ell.add(Point2D.add(corner, new Point2D(-2, -2)));
		ell.add(Point2D.add(corner, new Point2D(20, -2)));
		ell.add(Point2D.add(corner, new Point2D(20, 0)));
		ell.add(corner);
		return ell;
	}
	
	/**
	 * Compute the outline polygons for this set of patterns.
	 * @param layerConditions
	 * @param hatchedPolygons
	 * @param shield
	 * @return
	 */
	public PolygonList computeOutlines(int stl, PolygonList hatchedPolygons, boolean shield)
	{
		// No more additions or movements, please
		
		freeze();	
		
		// The shapes to outline.
		
		BooleanGridList slice = slice(stl, layerRules.getModelLayer());
		
		// Pick out the ones we need to do at this height
		
		slice = neededThisLayer(slice, false, false);
		
		if(slice.size() <= 0)
			return new PolygonList();
		
		PolygonList borderPolygons;
		
		// Are we building the raft under things?  If so, there is no border.
		
		if(layerRules.getLayingSupport())
		{
			borderPolygons = null;
		} else
		{
			BooleanGridList offBorder = slice.offset(layerRules, true, -1);
			borderPolygons = offBorder.borders();
		}


		// If we've got polygons to plot, amend them so they start in the middle 
		// of a hatch (this gives cleaner boundaries).  Also add the nose-wipe shield
		// if it's been asked for.
		
		if(borderPolygons != null && borderPolygons.size() > 0)
		{
			borderPolygons.middleStarts(hatchedPolygons, layerRules, slice);
			try
			{
				if(shield && Preferences.loadGlobalBool("Shield") || 
						(borderPolygons.polygon(0).getAttributes().getExtruder().getPurgeTime() <= 0 && layerRules.getMachineLayer() == 0))
					borderPolygons.add(0, shieldPolygon(borderPolygons.polygon(0).getAttributes()));
			} catch (Exception ex)
			{}
		}
		
		return borderPolygons;
	}

	
	/**
	 * Generate a set of pixel-map representations, one for each extruder, for
	 * STLObject stl at height z.
	 * 
	 * @param stlIndex
	 * @param z
	 * @param extruders
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private BooleanGridList slice(int stlIndex, int layer)
	{
		if(!frozen)
		{
			Debug.e("AllSTLsToBuild.slice() called when unfrozen!");
			freeze();
		}
		
		if(layer < 0)
			return new BooleanGridList();
		
		// Is the result in the cache?  If so, just use that.
		
		BooleanGridList result = cache.getSlice(layer, stlIndex);
		if(result != null)
			return result;
		
		// Haven't got it in the cache, so we need to compute it
		
		// Anything there?
		
		if(rectangles.get(stlIndex) == null)
			return new BooleanGridList();
		
		// Probably...
		
		double z = layerRules.getModelZ(layer) + layerRules.getZStep()*0.5;
		Extruder[] extruders = layerRules.getPrinter().getExtruders();
		result = new BooleanGridList();
		CSG2D csgp = null;
		PolygonList pgl = new PolygonList();
		int extruderID;
		
		// Bin the edges and CSGs (if any) by extruder ID.
		
		ArrayList<LineSegment>[] edges = new ArrayList[extruders.length];
		ArrayList<CSG3D>[] csgs = new ArrayList[extruders.length];
		Attributes[] atts = new Attributes[extruders.length];
		
		for(extruderID = 0; extruderID < extruders.length; extruderID++)
		{
			if(extruders[extruderID].getID() != extruderID)
				Debug.e("AllSTLsToBuild.slice(): extruder " + extruderID + "out of sequence: " + extruders[extruderID].getID());
			edges[extruderID] = new ArrayList<LineSegment>();
			csgs[extruderID] = new ArrayList<CSG3D>();
		}
		
		// Generate all the edges for STLObject i at this z
		
		STLObject stlObject = stls.get(stlIndex);
		Transform3D trans = stlObject.getTransform();
		Matrix4d m4 = new Matrix4d();
		trans.get(m4);

		//BranchGroup bg = stlObject.getSTL();

		
		for(int i = 0; i < stlObject.getCount(); i++)
		{
			BranchGroup bg1 = stlObject.getSTL(i);
			Attributes attr = (Attributes)(bg1.getUserData());
			atts[attr.getExtruder().getID()] = attr;
			CSG3D csg = stlObject.getCSG(i);
			if(csg != null)
				csgs[attr.getExtruder().getID()].add(csg.transform(m4));
			else
				recursiveSetEdges(attr.getPart(), trans, z, attr, edges);
		}
		
		// Turn them into lists of polygons, one for each extruder, then
		// turn those into pixelmaps.
		
		for(extruderID = 0; extruderID < edges.length; extruderID++)
		{
			// Deal with CSG shapes (much simpler and faster).
			
			for(int i = 0; i < csgs[extruderID].size(); i++)
			{
				csgp = CSG2D.slice(csgs[extruderID].get(i), z);
				result.add(new BooleanGrid(csgp, rectangles.get(stlIndex), atts[extruderID]));
			}
			
			// Deal with STL-generated edges
			
			if(edges[extruderID].size() > 0)
			{
				pgl = simpleCull(edges[extruderID]);

				if(pgl.size() > 0)
				{
					// Remove wrinkles

					pgl = pgl.simplify(Preferences.gridRes()*1.5);

					// Fix small radii

					pgl = pgl.arcCompensate();

					csgp = pgl.toCSG(Preferences.tiny());

					// We use the plan rectangle of the entire stl object to store the bitmap, even though this slice may be
					// much smaller than the whole.  This allows booleans on slices to be computed much more
					// quickly as each is in the same rectangle so the bit patterns match exactly.  But it does use more memory.

					result.add(new BooleanGrid(csgp, rectangles.get(stlIndex), pgl.polygon(0).getAttributes()));
				}
			}
		}
		
		// We may need this later...
		
		cache.setSlice(result, layer, stlIndex);
		
		return result;
	}

	
	public void destroyLayer() {}
	
	/**
	 * Add the edge where the plane z cuts the triangle (p, q, r) (if it does).
	 * Also update the triangulation of the object below the current slice used
	 * for the simulation window.
	 * @param p
	 * @param q
	 * @param r
	 * @param z
	 */
	private void addEdge(Point3d p, Point3d q, Point3d r, double z, Attributes att, ArrayList<LineSegment> edges[])
	{
		Point3d odd = null, even1 = null, even2 = null;
		int pat = 0;
		//boolean twoBelow = false;
		
		if(p.z < z)
			pat = pat | 1;
		if(q.z < z)
			pat = pat | 2;
		if(r.z < z)
			pat = pat | 4;
		
		switch(pat)
		{
		// All above
		case 0:
			return;
			
		// All below
		case 7:
			return;
			
		// q, r below, p above	
		case 6:
			//twoBelow = true;
		// p below, q, r above
		case 1:
			odd = p;
			even1 = q;
			even2 = r;
			break;
			
		// p, r below, q above	
		case 5:
			//twoBelow = true;
		// q below, p, r above	
		case 2:
			odd = q;
			even1 = r;
			even2 = p;
			break;

		// p, q below, r above	
		case 3:
			//twoBelow = true;
		// r below, p, q above	
		case 4:
			odd = r;
			even1 = p;
			even2 = q;
			break;
			
		default:
			Debug.e("addEdge(): the | function doesn't seem to work...");
		}
		
		// Work out the intersection line segment (e1 -> e2) between the z plane and the triangle
		
		even1.sub((Tuple3d)odd);
		even2.sub((Tuple3d)odd);
		double t = (z - odd.z)/even1.z;	
		Point2D e1 = new Point2D(odd.x + t*even1.x, odd.y + t*even1.y);	
		//Point3d e3_1 = new Point3d(e1.x(), e1.y(), z);
		//e1 = new Rr2Point(toGrid(e1.x()), toGrid(e1.y()));
		e1 = new Point2D(e1.x(), e1.y());
		t = (z - odd.z)/even2.z;
		Point2D e2 = new Point2D(odd.x + t*even2.x, odd.y + t*even2.y);
		//Point3d e3_2 = new Point3d(e2.x(), e2.y(), z);
		//e2 = new Rr2Point(toGrid(e2.x()), toGrid(e2.y()));
		e2 = new Point2D(e2.x(), e2.y());
		
		// Too short?
		if(!Point2D.same(e1, e2, Preferences.lessGridSquare()))
			edges[att.getExtruder().getID()].add(new LineSegment(e1, e2, att));
	}
	

	
	/**
	 * Run through a Shape3D and set edges from it at plane z
	 * Apply the transform first
	 * @param shape
	 * @param trans
	 * @param z
	 */
	private void addAllEdges(Shape3D shape, Transform3D trans, double z, Attributes att, ArrayList<LineSegment> edges[])
    {
        GeometryArray g = (GeometryArray)shape.getGeometry();
        Point3d p1 = new Point3d();
        Point3d p2 = new Point3d();
        Point3d p3 = new Point3d();
        Point3d q1 = new Point3d();
        Point3d q2 = new Point3d();
        Point3d q3 = new Point3d();
        
        if(g.getVertexCount()%3 != 0)
        {
        	Debug.e("addAllEdges(): shape3D with vertices not a multiple of 3!");
        }
        if(g != null)
        {
            for(int i = 0; i < g.getVertexCount(); i+=3) 
            {
                g.getCoordinate(i, p1);
                g.getCoordinate(i+1, p2);
                g.getCoordinate(i+2, p3);
                trans.transform(p1, q1);
                trans.transform(p2, q2);
                trans.transform(p3, q3);
                addEdge(q1, q2, q3, z, att, edges);
            }
        }
    }
	
	/**
	 * Unpack the Shape3D(s) from value and set edges from them
	 * @param value
	 * @param trans
	 * @param z
	 */
	private void recursiveSetEdges(Object value, Transform3D trans, double z, Attributes att, ArrayList<LineSegment> edges[]) 
    {
        if(value instanceof SceneGraphObject) 
        {
            SceneGraphObject sg = (SceneGraphObject)value;
            if(sg instanceof Group) 
            {
                Group g = (Group)sg;
                java.util.Enumeration<?> enumKids = g.getAllChildren( );
                while(enumKids.hasMoreElements())
                    recursiveSetEdges(enumKids.nextElement(), trans, z, att, edges);
            } else if (sg instanceof Shape3D) 
            {
                addAllEdges((Shape3D)sg, trans, z, att, edges);
            }
        }
    }

}
