package figtools

import com.typesafe.scalalogging.Logger
import figtools.ImageSegmenter.ImageSegment
import ij.ImagePlus
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.filter.ParticleAnalyzer

import scala.collection.mutable.ArrayBuffer
import ImageLog.{log, logStep}
import com.github.davidmoten.rtree.{Entries, RTree}
import com.github.davidmoten.rtree.geometry.Rectangle
import ij.gui.Roi

import scala.collection.JavaConverters._
import de.sciss.equal.Implicits._

import scala.collection.mutable

// TODO:
//   - look at overlapping missing panels to determine proper spacing
//   - check matching border colors
class GappedImageSegmenter extends ImageSegmenter {
  import GappedImageSegmenter._

  override def segment(imp: ImagePlus): Seq[ImageSegment] = {
    val imp2 = imp.duplicate()
    //log(imp2, "[GappedImageSegmenter] original image")

    // binarize the image using a threshold of 0.95
    val threshold = (255.0 * BinarizeThreshold).toInt
    val lut = (0 until 256).map{v=>if (v < threshold) 0 else 255.toByte.toInt}.toArray
    imp2.getProcessor.applyTable(lut)
    log(imp2, s"[GappedImageSegmenter] Binarize with $threshold threshold")

    val rt = new ResultsTable
    val particleAnalyzer = new ParticleAnalyzer(
      ParticleAnalyzer.CLEAR_WORKSHEET |
      ParticleAnalyzer.IN_SITU_SHOW,
      Measurements.MIN_MAX |
      Measurements.RECT,
      rt,
      0.0,
      Double.MaxValue)
    if (!particleAnalyzer.analyze(imp2)) throw new RuntimeException(
      "ParticleAnalyzer.analyze() returned false!")
    val particles = (0 until rt.getCounter).map{i=>
      Option(rt.getLabel(i)).getOrElse(s"Roi${i+1}")->
      new Roi(
        rt.getValue("BX", i).toInt,
        rt.getValue("BY", i).toInt,
        rt.getValue("Width", i).toInt,
        rt.getValue("Height", i).toInt)}
    log(imp2, s"[GappedImageSegmenter] Particle Analyzer", particles: _*)

    // Get the objects and iterate through them
    var segs = ArrayBuffer[ImageSegment]()
    val smallSegs = ArrayBuffer[ImageSegment]()
    (0 until rt.getCounter).foreach{i=>
      val bx = rt.getValue("BX", i).toInt
      val by = rt.getValue("BY", i).toInt
      val width = rt.getValue("Width", i).toInt
      val height = rt.getValue("Height", i).toInt

      if (width > imp2.getWidth.toDouble / ParticleThreshold &&
          height > imp2.getHeight.toDouble / ParticleThreshold)
      {
        if ((width*height).toDouble / (imp2.getWidth*imp2.getHeight).toDouble < largeParticleFilter) {
          val box = ImageSegmenter.Box(bx, by, bx+width, by+height)
          val segment = ImageSegment(imp, box)
          segs += segment
        }
      }
      else {
        val box = ImageSegmenter.Box(bx, by, bx+width, by+height)
        val segment = ImageSegment(imp, box)
        smallSegs += segment
      }
    }
    log(imp2,
      s"[GappedImageSegmenter] filter boxes smaller than (${(imp2.getWidth.toDouble / ParticleThreshold).toInt},${(imp2.getHeight.toDouble / ParticleThreshold).toInt})",
      segs.map{_.box.toRoi}: _*)

    val mergedSegs = mergeOverlappingSegs(segs)
    segs = ArrayBuffer(mergedSegs: _*)
    logger.info(s"mergedSegs=${pprint.apply(mergedSegs, height=1000)}")
    log(imp2, "[GappedImageSegmenter] merge overlapping segments", mergedSegs.map{_.box.toRoi}: _*)

    // temporarily eliminate small components
    val (larger, smaller) = segs.sortBy({seg=>
        (seg.box.x2 - seg.box.x) *
        (seg.box.y2 - seg.box.y)
      }).lastOption match
    {
      case Some(largest) =>
        val larger = segs.filter(seg=>
          (seg.box.x2 - seg.box.x).toDouble / (largest.box.x2 - largest.box.x).toDouble > BboxThreshold &&
          (seg.box.y2 - seg.box.y).toDouble / (largest.box.y2 - largest.box.y).toDouble > BboxThreshold)
        val smaller = segs.filter(seg=>
          !((seg.box.x2 - seg.box.x).toDouble / (largest.box.x2 - largest.box.x).toDouble > BboxThreshold &&
            (seg.box.y2 - seg.box.y).toDouble / (largest.box.y2 - largest.box.y).toDouble > BboxThreshold))
        (larger, smaller)
      case None =>
        logger.warn(s"No largest bounding box found!")
        (segs, Seq())
    }
    log(imp2, "[GappedImageSegmenter] temporarily eliminate small components", larger.map{s=>s.box.toRoi}: _*)

    // recover missing panels
    val imp3 = imp2.duplicate()
    val largerRtree = RTree.create[ImageSegment,Rectangle].
      add(larger.map{s=>Entries.entry(s, s.box.toRect)}.asJava)
    val recoverMissing = larger.flatMap{seg =>
      imp3.setRoi(seg.box.toRoi)
      val segArea = ((seg.box.x2 - seg.box.x) * (seg.box.y2 - seg.box.y)).toDouble
      val segHisto = imp3.getProcessor.getHistogram
      val segContent = segHisto(0).toDouble / segArea
      logger.info(s"segContent=$segContent")

      val up = ImageSegmenter.Box(seg.box.x,seg.box.y-seg.box.height,seg.box.x2,seg.box.y-1)
      imp3.setRoi(up.toRoi)
      val upHisto = imp3.getProcessor.getHistogram
      val upContent = upHisto(0).toDouble / segArea
      logger.info(s"upContent=$upContent")

      val down = ImageSegmenter.Box(seg.box.x,seg.box.y+seg.box.height+1,seg.box.x2,seg.box.y2+seg.box.height)
      imp3.setRoi(down.toRoi)
      val downHisto = imp3.getProcessor.getHistogram
      val downContent = downHisto(0).toDouble / segArea
      logger.info(s"downContent=$downContent")

      val left = ImageSegmenter.Box(seg.box.x-seg.box.width,seg.box.y,seg.box.x-1,seg.box.y2)
      imp3.setRoi(left.toRoi)
      val leftHisto = imp3.getProcessor.getHistogram
      val leftContent = leftHisto(0).toDouble / segArea
      logger.info(s"leftContent=$leftContent")

      val right = ImageSegmenter.Box(seg.box.x+seg.box.width+1,seg.box.y,seg.box.x2+seg.box.width,seg.box.y2)
      imp3.setRoi(right.toRoi)
      val rightHisto = imp3.getProcessor.getHistogram
      val rightContent = rightHisto(0).toDouble / segArea
      logger.info(s"rightContent=$rightContent")

      (if (math.abs(segContent-upContent) < ContentDiff &&
        upContent > ContentMin &&
        seg.box.height < seg.box.y &&
        Option(largerRtree.search(up.toRect).toBlocking.firstOrDefault(null)).isDefined)
        Seq(ImageSegment(imp, up))
      else Seq()) ++
        (if (math.abs(segContent-downContent) < ContentDiff &&
          downContent > ContentMin &&
          seg.box.y+seg.box.height*2 < imp3.getHeight &&
          Option(largerRtree.search(down.toRect).toBlocking.firstOrDefault(null)).isDefined)
          Seq(ImageSegment(imp, down))
        else Seq()) ++
        (if (math.abs(segContent-leftContent) < ContentDiff &&
          leftContent > ContentMin &&
          seg.box.width < seg.box.x &&
          Option(largerRtree.search(left.toRect).toBlocking.firstOrDefault(null)).isDefined)
          Seq(ImageSegment(imp, left))
        else Seq()) ++
        (if (math.abs(segContent-rightContent) < ContentDiff &&
          rightContent > ContentMin &&
          seg.box.x+seg.box.width*2 < imp3.getWidth &&
          Option(largerRtree.search(right.toRect).toBlocking.firstOrDefault(null)).isDefined)
          Seq(ImageSegment(imp, right))
        else Seq())
    }
    // TODO: fix recover missing panels code
    //segs = larger ++ recoverMissing
    segs = larger
    //log(imp2, "[GappedImageSegmenter] recover missing panels", recoverMissing.map{s=>s.box.toRoi}: _*)

    //check segmentation area
    val segmentArea = segs.map{s=>(s.box.x2-s.box.x)*(s.box.y2-s.box.y)}.sum
    val imageArea = imp2.getWidth * imp2.getHeight
    if (segmentArea.toDouble / imageArea.toDouble < SegmentAreaCutoff) {
      logger.warn(s"Image segment area is $segmentArea/$imageArea=${segmentArea.toDouble/imageArea.toDouble} is less than $SegmentAreaCutoff, skipping")
      return Seq()
    }

    // run small components recovery step
    log(imp2, "[GappedImageSegmenter] recover small components, segs",
      segs.map{s=>s.box.toRoi}: _*)
    log(imp2, "[GappedImageSegmenter] recover small components, smaller",
      smaller.map{s=>s.box.toRoi}: _*)
    log(imp2, "[GappedImageSegmenter] recover small components, smalleSegs",
      smallSegs.map{s=>s.box.toRoi}: _*)
    val toRecover = segs.map{s=>GappedImageSegmenter.Segment(s, skip=true)} ++
      (smaller ++ smallSegs).map{s=>GappedImageSegmenter.Segment(s,skip=false)}
    logger.info(s"Running recover small components step")
    val recovered = recoverSmallComponents(imp2, toRecover)
    logger.info(s"Finished running recover small components step")

    log(imp2, "[GappedImageSegmenter] recover small components",
      recovered.map{s=>s.segment.box.toRoi}: _*)
    val mergedRecovered = mergeOverlappingSegs(recovered.map{s=>s.segment})
    log(imp2, "[GappedImageSegmenter] recover small components, merged",
      mergedRecovered.map{s=>s.box.toRoi}: _*)
    segs = ArrayBuffer(mergedRecovered: _*)
    segs
  }
}

object GappedImageSegmenter {
  val pp = pprint.PPrinter(defaultWidth=40, defaultHeight=Int.MaxValue)

  val logger = Logger(getClass.getSimpleName)
  val BinarizeThreshold = 0.95
  val ParticleThreshold = 20.0
  val largeParticleFilter = 0.9
  val MergeThreshold = 0.1
  val BboxThreshold = 0.15
  val NewBoxPctCutoff = 0.2
  val SegmentAreaCutoff = 0.4
  val ContentDiff = 0.1
  val ContentMin = 0.01

  def mergeOverlappingSegs(segments: Seq[ImageSegment], useThreshold: Boolean = true): Seq[ImageSegment] = {
    // start with an empty output r-tree
    var rtree = RTree.create[ImageSegment,Rectangle]()
    // iterate through all entries in the input r-tree
    for (seg <- segments) {
      // look for overlapping segments that overlap by at least MergeThreshold area amount
      val overlaps = rtree.search(seg.box.toRect).toBlocking.getIterator.asScala.filter{overlaps=>
        !useThreshold || {
          val intersectionBox = ImageSegmenter.Box(
            math.max(seg.box.x, overlaps.value.box.x),
            math.max(seg.box.y, overlaps.value.box.y),
            math.min(seg.box.x2, overlaps.value.box.x2),
            math.min(seg.box.y2, overlaps.value.box.y2))
          val intersectionArea =
            (intersectionBox.x2 - intersectionBox.x) *
              (intersectionBox.y2 - intersectionBox.y)
          val minBoxArea = math.min(
            (seg.box.x2 - seg.box.x) * (seg.box.y2 - seg.box.y),
            (overlaps.value.box.x2 - overlaps.value.box.x) * (overlaps.value.box.y2 - overlaps.value.box.y))
          intersectionArea.toDouble / minBoxArea.toDouble > MergeThreshold
        }
      }.toSeq
      // build a merged segment
      val merged = ImageSegment(seg.imp,
        ImageSegmenter.Box(
          (Seq(seg.box.x) ++ overlaps.map{_.value.box.x}).min,
          (Seq(seg.box.y) ++ overlaps.map{_.value.box.y}).min,
          (Seq(seg.box.x2) ++ overlaps.map{_.value.box.x2}).max,
          (Seq(seg.box.y2) ++ overlaps.map{_.value.box.y2}).max))
      // remove all the segments that go into the merged segment
      rtree = rtree.delete(overlaps.asJava, true)
      // add the merged segment
      rtree = rtree.add(Entries.entry(merged, merged.box.toRect))
    }
    rtree.entries.toBlocking.getIterator.asScala.map{_.value}.toSeq
  }


  case class Segment(segment: ImageSegment, skip: Boolean)
  case class SegPair(segment: Segment, nearest: Segment)

  def recoverSmallComponents(imp: ImagePlus, segs: Seq[Segment]): Seq[Segment] = {
    // build the r-tree by merging all the overlapping components
    logger.info(s"building merged r-tree using ${segs.size} segs")
    var rtree = RTree.create[Segment,Rectangle]()
    for (seg <- segs) {
      val overlaps = rtree.search(seg.segment.box.toRect).toBlocking.getIterator.asScala.filter{overlaps=>
        val intersectionBox = ImageSegmenter.Box(
          math.max(seg.segment.box.x, overlaps.value.segment.box.x),
          math.max(seg.segment.box.y, overlaps.value.segment.box.y),
          math.min(seg.segment.box.x2, overlaps.value.segment.box.x2),
          math.min(seg.segment.box.y2, overlaps.value.segment.box.y2))
        val intersectionArea =
          (intersectionBox.x2 - intersectionBox.x) *
            (intersectionBox.y2 - intersectionBox.y)
        val minBoxArea = math.min(
          (seg.segment.box.x2 - seg.segment.box.x) * (seg.segment.box.y2 - seg.segment.box.y),
          (overlaps.value.segment.box.x2 - overlaps.value.segment.box.x) * (overlaps.value.segment.box.y2 - overlaps.value.segment.box.y))
        intersectionArea.toDouble / minBoxArea.toDouble > MergeThreshold
      }.toSeq
      // build a merged segment
      val merged = Segment(
        ImageSegment(seg.segment.imp,
          ImageSegmenter.Box(
            (Seq(seg.segment.box.x) ++ overlaps.map{_.value.segment.box.x}).min,
            (Seq(seg.segment.box.y) ++ overlaps.map{_.value.segment.box.y}).min,
            (Seq(seg.segment.box.x2) ++ overlaps.map{_.value.segment.box.x2}).max,
            (Seq(seg.segment.box.y2) ++ overlaps.map{_.value.segment.box.y2}).max)),
        seg.skip || overlaps.exists{_.value.skip})
      // remove all the segments that go into the merged segment
      rtree = rtree.delete(overlaps.asJava, true)
      // add the merged segment
      rtree = rtree.add(Entries.entry(merged, merged.segment.box.toRect))
    }
    logger.info(s"built merged r-tree with ${rtree.size} elements")

    // define case class orderings for the sorted set
    implicit def impOrder: Ordering[ImagePlus] =
      Ordering.by(i => i.asInstanceOf[AnyRef].hashCode)
    implicit def boxOrder: Ordering[ImageSegmenter.Box[Int]] =
      Ordering.by(b => (b.x, b.x2, b.y, b.y2))
    implicit def imageSegOrder: Ordering[ImageSegment] =
      Ordering.by(i => (i.imp, i.box))
    implicit def segOrder: Ordering[Segment] =
      Ordering.by(s => (s.segment, s.skip))
    // sort by distance, then by merged area
    implicit def segPairOrder: Ordering[SegPair] =
      Ordering.by{p =>
        val box = ImageSegmenter.Box(
          math.min(p.segment.segment.box.x, p.nearest.segment.box.x),
          math.min(p.segment.segment.box.y, p.nearest.segment.box.y),
          math.max(p.segment.segment.box.x2, p.nearest.segment.box.x2),
          math.max(p.segment.segment.box.y2, p.nearest.segment.box.y2))
        (p.segment.segment.box.toRect.distance(p.nearest.segment.box.toRect),
          (box.x2-box.x+1) * (box.y2-box.y+1),
          p.segment,
          p.nearest)
      }
    val pairlist = rtree.entries.toBlocking.getIterator.asScala.map{_.value}.
        flatMap(entry=>{
          val nearest = rtree.nearest(entry.segment.box.toRect, Double.MaxValue, Int.MaxValue).
            filter{e=>
              (e.value.segment.box !== entry.segment.box) &&
                !(e.value.skip && entry.skip)}.
            toBlocking.getIterator.asScala.toSeq
          if (nearest.isEmpty) None else Some(SegPair(
            entry,
            nearest.head.value))
        }).toSeq
    val segpairs = mutable.SortedSet[SegPair](pairlist: _*)

    // keep an index of segment -> segpair
    val segIndex = mutable.Map[Segment,SegPair](segpairs.map{p=>p.segment->p}.toSeq: _*)
    // keep an index of neighbor -> segpairs
    val nearestIndex = new mutable.HashMap[Segment, mutable.Set[SegPair]] with mutable.MultiMap[Segment, SegPair]
    segpairs.foreach{p=> nearestIndex.addBinding(p.nearest, p) }
    // process all the segpairs
    while (segpairs.nonEmpty) {
      // pop the first pair from the set
      val pair = segpairs.head
      segpairs -= pair
      // merge the pair's boxes
      val box = ImageSegmenter.Box(
        math.min(pair.segment.segment.box.x, pair.nearest.segment.box.x),
        math.min(pair.segment.segment.box.y, pair.nearest.segment.box.y),
        math.max(pair.segment.segment.box.x2, pair.nearest.segment.box.x2),
        math.max(pair.segment.segment.box.y2, pair.nearest.segment.box.y2))
      // get the set of r-tree entries that will be merged by joining this segpair
      val toMerge = Seq(pair.segment, pair.nearest)
      // create the merged segment
      val mergedSegment = Segment(ImageSegment(pair.segment.segment.imp, box), toMerge.exists{_.skip})
      // delete the to-be-merged segments from rtree, segpairs, and segindex
      rtree = rtree.delete(toMerge.map{tm=>Entries.entry(tm, tm.segment.box.toRect)}.asJava, true)
      rtree = rtree.add(Entries.entry(mergedSegment, box.toRect))
      segpairs --= toMerge.flatMap{tm=>segIndex.get(tm)}
      segIndex --= toMerge

      // add the newly merged setgment to segpairs, segIndex, rtree, and nearestIndex
      val mergedNearest = rtree.nearest(mergedSegment.segment.box.toRect, Double.MaxValue, Int.MaxValue).
          filter{e=>
            (e.value.segment.box !== mergedSegment.segment.box) &&
              !(e.value.skip && mergedSegment.skip)}.
          toBlocking.getIterator.asScala.toSeq.headOption
      for (mn <- mergedNearest) {
        // add new merged pair
        val mergedPair = SegPair(mergedSegment, mn.value)
        segpairs += mergedPair
        segIndex += mergedSegment->mergedPair
        nearestIndex.addBinding(mn.value, mergedPair)
      }
      // update nearest neighbors for the new merged segment pair and any existing segment pairs
      for {
        tm <- toMerge
        sps <- nearestIndex.get(tm)
      } {
        nearestIndex -= tm
        for (sp <- sps) {
          if (segpairs.contains(sp)) {
            segpairs -= sp
            val nearests = rtree.nearest(sp.segment.segment.box.toRect, Double.MaxValue, Int.MaxValue).
              filter{e=>
                (e.value.segment.box !== sp.segment.segment.box) &&
                  !(e.value.skip && sp.segment.skip)}.
              toBlocking.getIterator.asScala.toSeq.headOption
            for (nearest <- nearests) {
              val updatedSp = SegPair(sp.segment, nearest.value)
              segpairs += updatedSp
              segIndex += sp.segment->updatedSp
              nearestIndex.addBinding(nearest.value, updatedSp)
            }
          }
        }
      }
      //val rois = rtree.entries.toBlocking.getIterator.asScala.map{_.value.segment.box.toRoi}.toSeq
      //logStep(imp, s"[GappedImageSegmenter] recover small components step, rois.size=${rois.size}", rois: _*)
    }
    // return the remaining r-tree entries
    logger.info(s"return r-tree entries")
    rtree.entries.toBlocking.getIterator.asScala.map{_.value}.toSeq
  }
}
