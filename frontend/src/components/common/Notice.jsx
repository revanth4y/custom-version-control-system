import { Box, Octicon, Text } from "@primer/react";
import { InfoIcon, CheckCircleIcon, AlertIcon, StopIcon } from "@primer/octicons-react";

const VARIANTS = {
  info: { icon: InfoIcon, fg: "fg.muted", bg: "canvas.overlay", border: "border.default" },
  success: { icon: CheckCircleIcon, fg: "success.fg", bg: "success.subtle", border: "success.muted" },
  warning: { icon: AlertIcon, fg: "attention.fg", bg: "attention.subtle", border: "attention.muted" },
  danger: { icon: StopIcon, fg: "danger.fg", bg: "danger.subtle", border: "danger.muted" },
};

/**
 * An inline message.
 *
 * Used instead of Primer's Flash for informational notices. Flash tints its
 * default variant with the accent colour, which works when the accent is blue
 * but not here: ember and attention are both warm, so an "info" banner and a
 * "warning" banner came out as near-identical browns and stopped carrying any
 * meaning. Informational notices are therefore neutral, leaving colour to say
 * something only when there is something to say.
 */
const Notice = ({ variant = "info", icon, children, sx = {} }) => {
  const style = VARIANTS[variant] ?? VARIANTS.info;

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 2,
        px: 3,
        py: "12px",
        bg: style.bg,
        border: "1px solid",
        borderColor: style.border,
        borderRadius: 2,
        color: "fg.default",
        fontSize: 1,
        ...sx,
      }}
    >
      <Octicon icon={icon ?? style.icon} sx={{ color: style.fg, mt: "2px", flexShrink: 0 }} />
      <Text sx={{ minWidth: 0 }}>{children}</Text>
    </Box>
  );
};

export default Notice;
