package vn.banking.transfer.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

final class Paging {

    private Paging() {
    }

    /** Drops any client-supplied sort: ordering is fixed by the repository query. */
    static Pageable unsorted(Pageable p) {
        return PageRequest.of(p.getPageNumber(), p.getPageSize());
    }
}
