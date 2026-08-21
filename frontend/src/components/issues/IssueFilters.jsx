import { Box, SegmentedControl, TextInput } from "@primer/react";
import { SearchIcon } from "@primer/octicons-react";

import { StatusFilter } from "../../utils/issues";

/**
 * Choosing which issues to look at.
 *
 * The counts come from the list already loaded rather than from a request; no
 * endpoint reports them, and since there is no pagination the whole list is
 * here anyway.
 */
const ORDER = [StatusFilter.OPEN, StatusFilter.CLOSED, StatusFilter.ALL];

const IssueFilters = ({ status, query, counts, onStatusChange, onQueryChange }) => {
  const label = {
    [StatusFilter.OPEN]: `Open ${counts.open}`,
    [StatusFilter.CLOSED]: `Closed ${counts.closed}`,
    [StatusFilter.ALL]: `All ${counts.total}`,
  };

  return (
    <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center", mb: 3 }}>
      <SegmentedControl aria-label="Filter issues by state">
        {ORDER.map((value) => (
          <SegmentedControl.Button
            key={value}
            selected={status === value}
            onClick={() => onStatusChange(value)}
          >
            {label[value]}
          </SegmentedControl.Button>
        ))}
      </SegmentedControl>

      <Box sx={{ flex: "1 1 220px", minWidth: 0 }}>
        <TextInput
          block
          leadingVisual={SearchIcon}
          value={query}
          placeholder="Search titles, or an issue number"
          aria-label="Search issues"
          onChange={(event) => onQueryChange(event.target.value)}
        />
      </Box>
    </Box>
  );
};

export default IssueFilters;
