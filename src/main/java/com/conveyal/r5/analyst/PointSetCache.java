package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

/**
 * A local in-memory cache for PointSets, which are loaded from persistent storage on S3.
 * It will load either gridded or freeform pointsets, depending on the file extension of the S3 key.
 * Each instance of PointSetCache reads from a single S3 bucket specified at construction.
 */
public class PointSetCache {
    private static final Logger LOG = LoggerFactory.getLogger(PointSetCache.class);

    /** How large the cache should be. Should be large enough to fit all field of a project */
    private static final int CACHE_SIZE = 200;

    private final AmazonS3 s3;

    private LoadingCache<String, PointSet> cache = CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                // Lambda functions cannot be used here because CacheLoader has multiple overrideable methods.
                .build(new CacheLoader<String, PointSet>() {
                    @Override
                    public PointSet load(String s) throws Exception {
                        return loadPointSet(s);
                    }
                });

    private final String region;
    private final String bucket;

    public PointSetCache(String region, String bucket) {
        this.bucket = bucket;
        this.region = region;
        s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();
    }

    private PointSet loadPointSet(String key) throws IOException {
        S3Object obj = s3.getObject(bucket, key);
        // If the object does not exist on S3, getObject will throw an exception which will be caught in the
        // PointSetCache.get method. Grids are gzipped on S3.
        InputStream is = new GZIPInputStream(new BufferedInputStream(obj.getObjectContent()));
        if (key.endsWith(Grid.FILE_EXTENSION)) {
            return Grid.read(is);
        } else if (key.endsWith(FreeFormPointSet.FILE_EXTENSION)) {
            return new FreeFormPointSet(is);
        } else {
            throw new RuntimeException("Unrecognized file extension in object key: " + key);
        }
    }

    public PointSet get (String key) {
        try {
            return cache.get(key);
        } catch (ExecutionException e) {
            LOG.error("Error retrieving destinationPointSetId {}", key, e);
            throw new RuntimeException(e);
        }
    }
}
