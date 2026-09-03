import { Box, Text } from "@primer/react";
import Octicon from "../common/Octicon";

/**
 * One figure, and what it is.
 *
 * `hint` carries the caveat when a number needs one — a truncated scan, a
 * metric nothing was verified for. A card that showed the number alone would be
 * stating something the data does not support.
 */
const StatCard = ({ icon, label, value, hint }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      p: 3,
      bg: "canvas.default",
      minWidth: 0,
    }}
  >
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1 }}>
      {icon && <Octicon icon={icon} size={14} sx={{ color: "fg.muted" }} />}
      <Text sx={{ fontSize: 0, color: "fg.muted", textTransform: "uppercase", letterSpacing: "0.04em" }}>
        {label}
      </Text>
    </Box>
    <Text
      sx={{
        fontSize: 4,
        fontWeight: 600,
        color: "fg.default",
        lineHeight: 1.2,
        display: "block",
        wordBreak: "break-word",
      }}
    >
      {value}
    </Text>
    {hint && (
      <Text sx={{ fontSize: 0, color: "fg.muted", display: "block", mt: 1 }}>{hint}</Text>
    )}
  </Box>
);

/** A responsive grid that never forces a horizontal scrollbar. */
export const StatGrid = ({ children }) => (
  <Box
    sx={{
      display: "grid",
      gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
      gap: 3,
    }}
  >
    {children}
  </Box>
);

export default StatCard;
