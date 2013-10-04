/*
 * Copyright 1998-2013 University Corporation for Atmospheric Research/Unidata
 *
 *  Portions of this software were developed by the Unidata Program at the
 *  University Corporation for Atmospheric Research.
 *
 *  Access and use of this software shall impose the following obligations
 *  and understandings on the user. The user is granted the right, without
 *  any fee or cost, to use, copy, modify, alter, enhance and distribute
 *  this software, and any derivative works thereof, and its supporting
 *  documentation for any purpose whatsoever, provided that this entire
 *  notice appears in all copies of the software, derivative works and
 *  supporting documentation.  Further, UCAR requests that the user credit
 *  UCAR/Unidata in any publications that result from the use of this
 *  software or in any product that includes this software. The names UCAR
 *  and/or Unidata, however, may not be used in any advertising or publicity
 *  to endorse or promote any products or commercial entity unless specific
 *  written permission is obtained from UCAR/Unidata. The user also
 *  understands that UCAR/Unidata is not obligated to provide the user with
 *  any support, consulting, training or assistance of any kind with regard
 *  to the use, operation and performance of this software nor to provide
 *  the user with any updates, revisions, new versions or "bug fixes."
 *
 *  THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 *  INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 *  FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *  NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 *  WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package thredds.server.ncSubset.controller;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import thredds.server.ncSubset.exception.InvalidBBOXException;
import thredds.server.ncSubset.exception.NcssException;
import thredds.server.ncSubset.exception.OutOfBoundariesException;
import thredds.server.ncSubset.exception.RequestTooLargeException;
import thredds.server.ncSubset.exception.TimeOutOfWindowException;
import thredds.server.ncSubset.exception.UnsupportedOperationException;
import thredds.server.ncSubset.exception.VariableNotContainedInDatasetException;
import thredds.server.ncSubset.params.GridDataRequestParamsBean;
import thredds.servlet.ThreddsConfig;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.nc2.NetcdfFileWriter;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.grid.CFGridWriter;
import ucar.nc2.dt.grid.GridDataset;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateRange;
import ucar.unidata.geoloc.LatLonPointImpl;
import ucar.unidata.geoloc.LatLonRect;
import ucar.unidata.geoloc.ProjectionRect;

/**
 * @author mhermida
 * 
 */
class GridDataStream extends GridDatasetSubsetter {

	/*
	 * Compression rate used to estimate the filesize of netcdf4 compressed
	 * files
	 */
	static private final short ESTIMATED_C0MPRESION_RATE = 5;

	private GridDataset gds;
	
	private String requestPathInfo;

	private long maxFileDownloadSize;

	private GridDataStream(GridDataset gds, String requestPathInfo) {
		this.gds = gds;
		this.requestPathInfo = requestPathInfo;
	}

	/**
	 * 
	 * Returns the response file
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws NcssException
	 * @throws VariableNotContainedInDatasetException
	 * @throws IOException
	 * @throws ParseException
	 * @throws InvalidRangeException
	 */
	File getResponseFile(HttpServletRequest request,
			HttpServletResponse response, GridDataRequestParamsBean params,
			NetcdfFileWriter.Version version)
			throws VariableNotContainedInDatasetException, NcssException,
			InvalidRangeException, ParseException, IOException {

		if(!checkRequestedVars(gds, params) && params.getVertCoord() != null )
			throw new UnsupportedOperationException("The variables requested: " + params.getVar() + " have different vertical levels. Grid requests with vertCoord must have variables with same vertical levels.");
			
		File netcdfResult = null;
		if (isSpatialSubset(params)) {
			netcdfResult = spatialSubset(params, version);
		} else {
			netcdfResult = coordinatesSubset(params, response, version);
		}

		return netcdfResult;
	}

	private boolean isSpatialSubset(GridDataRequestParamsBean params)
			throws InvalidBBOXException {

		boolean spatialSubset = false;
		int contValid = 0;
		if (params.getNorth() != null)
			contValid++;
		if (params.getSouth() != null)
			contValid++;
		if (params.getEast() != null)
			contValid++;
		if (params.getWest() != null)
			contValid++;

		if (contValid == 4) {
			if (params.getNorth() < params.getSouth()) {
				throw new InvalidBBOXException(
						"Invalid bbox. Bounding Box must have north > south");
			}
			if (params.getEast() < params.getWest()) {
				throw new InvalidBBOXException(
						"Invalid bbox. Bounding Box must have east > west; if crossing 180 meridian, use east boundary > 180");
			}
			spatialSubset = true;
		} else {
			if (contValid > 0)
				throw new InvalidBBOXException(
						"Invalid bbox. All params north, south, east and west must be provided");
			else { // no bbox provided --> is spatialSubsetting
				if (params.getMaxx() == null && params.getMinx() == null
						&& params.getMaxy() == null && params.getMiny() == null)
					spatialSubset = true;
			}
		}
		return spatialSubset;
	}

	private File spatialSubset(GridDataRequestParamsBean params,
			NetcdfFileWriter.Version version) throws RequestTooLargeException,
			OutOfBoundariesException, InvalidRangeException, ParseException,
			IOException, VariableNotContainedInDatasetException,
			InvalidBBOXException, TimeOutOfWindowException {

		LatLonRect maxBB = gds.getBoundingBox();
		LatLonRect requestedBB = setBBForRequest(params, gds);

		boolean hasBB = !ucar.nc2.util.Misc.closeEnough(requestedBB
				.getUpperRightPoint().getLatitude(), maxBB.getUpperRightPoint()
				.getLatitude())
				|| !ucar.nc2.util.Misc.closeEnough(requestedBB
						.getLowerLeftPoint().getLatitude(), maxBB
						.getLowerLeftPoint().getLatitude())
				|| !ucar.nc2.util.Misc.closeEnough(requestedBB
						.getUpperRightPoint().getLongitude(), maxBB
						.getUpperRightPoint().getLongitude())
				|| !ucar.nc2.util.Misc.closeEnough(requestedBB
						.getLowerLeftPoint().getLongitude(), maxBB
						.getLowerLeftPoint().getLongitude());

		// Don't check this...
		// if (checkBB(maxBB, requestedBB)) {

		Range zRange = null;
		// Request with zRange --> adds a limitation: only variables with the
		// same vertical level???
		if (params.getVertCoord() != null || params.getVertStride() > 1)
			zRange = getZRange(gds, params.getVertCoord(),
					params.getVertStride(), params.getVar());

		List<CalendarDate> wantedDates = getRequestedDates(gds, params);
		CalendarDateRange wantedDateRange = null;

		if (!wantedDates.isEmpty())
			wantedDateRange = CalendarDateRange.of(wantedDates.get(0),
					wantedDates.get(wantedDates.size() - 1));

		CFGridWriter writer = new CFGridWriter();
		maxFileDownloadSize = ThreddsConfig.getBytes(
				"NetcdfSubsetService.maxFileDownloadSize", -1L);
		if (maxFileDownloadSize > 0) {
			long estimatedSize = writer.makeGridFileSizeEstimate(gds,
					params.getVar(), hasBB ? requestedBB : null,
					params.getHorizStride(), zRange, wantedDateRange,
					params.getTimeStride(), params.isAddLatLon());
			if (version == NetcdfFileWriter.Version.netcdf4) {
				estimatedSize /= ESTIMATED_C0MPRESION_RATE;
			}
			if (estimatedSize > maxFileDownloadSize) {
				throw new RequestTooLargeException("NCSS response too large = "
						+ estimatedSize + " max = " + maxFileDownloadSize);
			}
		}

		return makeGridFile(new CFGridWriter(), gds, params.getVar(),
				hasBB ? requestedBB : null, params.getHorizStride(), zRange,
				wantedDateRange, params.getTimeStride(), params.isAddLatLon(),
				version);
		// }
	}

	private File coordinatesSubset(GridDataRequestParamsBean params,
			HttpServletResponse response, NetcdfFileWriter.Version version)
			throws OutOfBoundariesException, ParseException,
			InvalidRangeException, RequestTooLargeException, IOException,
			InvalidBBOXException, TimeOutOfWindowException {

		// Check coordinate params: maxx, maxy, minx, miny
		Double minx = params.getMinx();
		Double maxx = params.getMaxx();
		Double miny = params.getMiny();
		Double maxy = params.getMaxy();

		int contValid = 0;
		if (minx != null)
			contValid++;
		if (maxx != null)
			contValid++;
		if (miny != null)
			contValid++;
		if (maxy != null)
			contValid++;

		if (contValid == 4) {
			if (minx > maxx) {
				throw new InvalidBBOXException(
						"Invalid bbox. Bounding Box must have minx < maxx");
			}
			if (miny > maxy) {
				throw new InvalidBBOXException(
						"Invalid bbox. Bounding Box must have miny < maxy");
			}

		} else {
			throw new InvalidBBOXException(
					"Invalid bbox. All params minx, maxx. miny, maxy must be provided");
		}

		ProjectionRect rect = new ProjectionRect(minx, miny, maxx, maxy);

		Range zRange = null;
		// Request with zRange --> adds a limitation: only variables with the
		// same vertical level???
		if (params.getVertCoord() != null || params.getVertStride() > 1)
			zRange = getZRange(gds, params.getVertCoord(),
					params.getVertStride(), params.getVar());

		List<CalendarDate> wantedDates = getRequestedDates(gds, params);
		CalendarDateRange wantedDateRange = CalendarDateRange.of(
				wantedDates.get(0), wantedDates.get(wantedDates.size() - 1));

		CFGridWriter writer = new CFGridWriter();
		maxFileDownloadSize = ThreddsConfig.getBytes(
				"NetcdfSubsetService.maxFileDownloadSize", -1L);
		if (maxFileDownloadSize > 0) {
			long estimatedSize = writer.makeGridFileSizeEstimate(gds,
					params.getVar(), rect, params.getHorizStride(), zRange,
					wantedDateRange, params.getTimeStride(),
					params.isAddLatLon());
			if (version == NetcdfFileWriter.Version.netcdf4) {
				estimatedSize /= ESTIMATED_C0MPRESION_RATE;
			}
			if (estimatedSize > maxFileDownloadSize) {
				throw new RequestTooLargeException("NCSS response too large = "
						+ estimatedSize + " max = " + maxFileDownloadSize);
			}
		}

		String filename = gds.getLocationURI();
		int pos = filename.lastIndexOf("/");
		filename = filename.substring(pos + 1);
		if (!filename.endsWith(".nc"))
			filename = filename + ".nc";

		Random random = new Random(System.currentTimeMillis());
		int randomInt = random.nextInt();
		String pathname = Integer.toString(randomInt) + "/" + filename;
		File ncFile = NcssDiskCache.getInstance().getDiskCache()
				.getCacheFile(pathname);
		String cacheFilename = ncFile.getPath();
		//String url = buildCacheUrl(pathname);
		//httpHeaders.set("Content-Location", url);
		//httpHeaders.set("Content-Disposition", "attachment; filename=\""
		//		+ filename + "\"");
		writer.makeFile(cacheFilename, gds, params.getVar(), rect,
				params.getHorizStride(), zRange, wantedDateRange, 1,
				params.isAddLatLon(), version);
		return new File(cacheFilename);
	}

	private LatLonRect setBBForRequest(GridDataRequestParamsBean params,
			GridDataset gds) throws InvalidBBOXException {

		if (params.getNorth() == null && params.getSouth() == null
				&& params.getWest() == null && params.getEast() == null)
			return gds.getBoundingBox();

		return new LatLonRect(new LatLonPointImpl(params.getSouth(),
				params.getWest()), params.getNorth() - params.getSouth(),
				params.getEast() - params.getWest());
	}

	private Range getZRange(GridDataset gds, Double verticalCoord,
			Integer vertStride, List<String> vars)
			throws OutOfBoundariesException {

		boolean hasVerticalCoord = false;
		Range zRange = null;

		if (verticalCoord != null) {
			hasVerticalCoord = !Double.isNaN(verticalCoord);
			// allow a vert level to be specified - but only one, and look in
			// first 3D var with nlevels > 1
			if (hasVerticalCoord) {
				try {
					for (String varName : vars) {
						GridDatatype grid = gds.findGridDatatype(varName);
						GridCoordSystem gcs = grid.getCoordinateSystem();
						CoordinateAxis1D vaxis = gcs.getVerticalAxis();
						if (vaxis != null && vaxis.getSize() > 1) {
							int bestIndex = -1;
							double bestDiff = Double.MAX_VALUE;
							for (int i = 0; i < vaxis.getSize(); i++) {
								double diff = Math.abs(vaxis.getCoordValue(i)
										- verticalCoord);
								if (diff < bestDiff) {
									bestIndex = i;
									bestDiff = diff;
								}
							}
							if (bestIndex >= 0)
								zRange = new Range(bestIndex, bestIndex);
						}
					}
				} catch (InvalidRangeException ire) {
					throw new OutOfBoundariesException(
							"Invalid vertical level: " + ire.getMessage());
				}
				// there's also a vertStride, but not needed since only 1D slice
				// is allowed
			}
		} else {// No vertical range was provided, we get the zRange with the
				// zStride (1 by default)

			if (vertStride > 1) {
				try {
					zRange = new Range(0, 0, vertStride);
					for (String varName : vars) {
						GridDatatype grid = gds.findGridDatatype(varName);
						GridCoordSystem gcs = grid.getCoordinateSystem();
						CoordinateAxis1D vaxis = gcs.getVerticalAxis();
						if (vaxis != null) {
							// Range vRange = new Range(0,
							// (int)vaxis.getSize()-1, 1);
							zRange = new Range(
									zRange.first(),
									zRange.last() > vaxis.getSize() ? zRange
											.last() : (int) vaxis.getSize() - 1,
									vertStride);
						}
					}
				} catch (InvalidRangeException ire) {
					throw new OutOfBoundariesException(
							"Invalid vertical stride: " + ire.getMessage());
				}

			}
		}

		return zRange;
	}

	private File makeGridFile(CFGridWriter writer, GridDataset gds,
			List<String> vars, LatLonRect bbox, Integer horizStride,
			Range zRange, CalendarDateRange dateRange, Integer timeStride,
			boolean addLatLon, NetcdfFileWriter.Version version)
			throws RequestTooLargeException, InvalidRangeException, IOException {

		Random random = new Random(System.currentTimeMillis());
		int randomInt = random.nextInt();

		String filename = getFileNameForResponse(version);
		String pathname = Integer.toString(randomInt) + "/" + filename;
		File ncFile = NcssDiskCache.getInstance().getDiskCache()
				.getCacheFile(pathname);

		String cacheFilename = ncFile.getPath();

		//String url = buildCacheUrl(pathname);

		//httpHeaders.set("Content-Location", url);
		//httpHeaders.set("Content-Disposition", "attachment; filename=\""
		//		+ filename + "\"");

		writer.makeFile(cacheFilename, gds, vars, bbox, horizStride, zRange,
				dateRange, timeStride, addLatLon, version);

		return new File(cacheFilename);

	}

	private String getFileNameForResponse(NetcdfFileWriter.Version version) {
		String fileExtension = ".nc";

		if (version == NetcdfFileWriter.Version.netcdf4) {
			fileExtension = ".nc4";
		}

		String[] tmp = requestPathInfo.split("/");
		StringBuilder sb = new StringBuilder();
		sb.append(tmp[tmp.length - 2]).append("_").append(tmp[tmp.length - 1]);
		String filename = sb.toString().split("\\.")[0] + fileExtension;
		return filename;
	}

	public static GridDataStream valueOf(GridDataset gds, String requestPathInfo) {
		return new GridDataStream(gds, requestPathInfo);
	}
}