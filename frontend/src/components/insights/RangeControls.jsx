import { Box, Button, FormControl, TextInput } from "@primer/react";

/**
 * The window and the grain.
 *
 * Both are the server's defaults until somebody changes them: leaving the dates
 * empty sends no parameters at all, so the API decides rather than the browser
 * guessing what "recent" means.
 */
const RangeControls = ({ from, to, bucket, onFrom, onTo, onBucket, onApply, onReset, pending }) => (
  <Box
    sx={{
      display: "flex",
      alignItems: "flex-end",
      gap: 2,
      flexWrap: "wrap",
      mb: 3,
    }}
  >
    <FormControl>
      <FormControl.Label>From</FormControl.Label>
      <TextInput
        type="date"
        value={from}
        onChange={(event) => onFrom(event.target.value)}
        aria-label="Range start"
      />
    </FormControl>

    <FormControl>
      <FormControl.Label>To</FormControl.Label>
      <TextInput
        type="date"
        value={to}
        onChange={(event) => onTo(event.target.value)}
        aria-label="Range end"
      />
    </FormControl>

    {onBucket && (
      <Box role="group" aria-label="Bucket" sx={{ display: "flex", gap: 1 }}>
        {["day", "week"].map((grain) => (
          <Button
            key={grain}
            variant={bucket === grain ? "primary" : "default"}
            aria-pressed={bucket === grain}
            onClick={() => onBucket(grain)}
          >
            {grain === "day" ? "Daily" : "Weekly"}
          </Button>
        ))}
      </Box>
    )}

    <Button onClick={onApply} disabled={pending}>
      {pending ? "Loading..." : "Apply"}
    </Button>
    <Button variant="invisible" onClick={onReset} disabled={pending}>
      Reset
    </Button>
  </Box>
);

export default RangeControls;
