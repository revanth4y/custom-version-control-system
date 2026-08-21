import { Box, Heading, Text } from "@primer/react";

import GitForgeMark from "../components/common/GitForgeMark";

/**
 * Frame shared by sign-in and sign-up.
 *
 * These two pages are the first thing anyone sees, and any difference between
 * them reads as a glitch when moving from one to the other — so the frame is
 * defined once and only the form inside changes.
 */
const AuthLayout = ({ title, subtitle, children, footer }) => (
  <Box
    sx={{
      minHeight: "100vh",
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      bg: "canvas.default",
      px: 3,
      py: 5,
    }}
  >
    <Box sx={{ width: "100%", maxWidth: "352px" }}>
      <Box sx={{ textAlign: "center", mb: 4 }}>
        <Box sx={{ display: "flex", justifyContent: "center", mb: 3, color: "accent.fg" }}>
          <GitForgeMark size={40} title="GitForge" />
        </Box>
        <Heading as="h1" sx={{ fontSize: 3, fontWeight: 600, mb: 1 }}>
          {title}
        </Heading>
        {subtitle && (
          <Text sx={{ color: "fg.muted", fontSize: 1 }}>{subtitle}</Text>
        )}
      </Box>

      <Box
        sx={{
          bg: "canvas.subtle",
          border: "1px solid",
          borderColor: "border.default",
          borderRadius: 2,
          p: 4,
        }}
      >
        {children}
      </Box>

      {footer && (
        <Box
          sx={{
            mt: 3,
            p: 3,
            textAlign: "center",
            border: "1px solid",
            borderColor: "border.muted",
            borderRadius: 2,
            fontSize: 1,
            color: "fg.muted",
          }}
        >
          {footer}
        </Box>
      )}
    </Box>
  </Box>
);

export default AuthLayout;
