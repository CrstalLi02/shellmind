package com.shellmind.api;

import com.shellmind.api.dto.SshFileContentResponseDTO;
import com.shellmind.api.dto.SshFileTreeResponseDTO;
import com.shellmind.api.response.Response;

/**
 * SSH file-browser service API
 */
public interface ISshFileService {

    /**
     * List a directory (current level only, lazy-loaded on demand)
     *
     * @param connectionId connection ID
     * @param path         directory path; empty defaults to the user home directory
     */
    Response<SshFileTreeResponseDTO> tree(String connectionId, String path);

    /**
     * Read file content (text)
     *
     * @param connectionId connection ID
     * @param path         file path
     */
    Response<SshFileContentResponseDTO> content(String connectionId, String path);

    /** Read file content (supports chunked offset/limit) */
    Response<SshFileContentResponseDTO> contentChunk(String connectionId, String path, Long offset, Integer limit);

}
