import { Component } from "react";
import { Box, Button, Heading, Text } from "@primer/react";
import Octicon from "./Octicon";
import { AlertIcon } from "@primer/octicons-react";

/**
 * Stops one broken component from taking the whole application with it.
 *
 * Without a boundary anywhere in the tree, React unmounts everything when a
 * render throws, and the user is left looking at a blank white page with no
 * indication that anything happened. A contained failure that says so, and
 * offers a way back, is recoverable; a blank page is not.
 *
 * A class because this is the one thing hooks cannot express.
 */
class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // Left in the console deliberately: this is the only record of a crash
    // that would otherwise be invisible once the fallback replaces the page.
    console.error("Unhandled error while rendering", error, info?.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;

    return (
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: 2,
          textAlign: "center",
          minHeight: "60vh",
          px: 3,
        }}
      >
        <Octicon icon={AlertIcon} size={24} sx={{ color: "danger.fg" }} />
        <Heading as="h1" sx={{ fontSize: 3, fontWeight: 600 }}>
          This page stopped working
        </Heading>
        <Text sx={{ color: "fg.muted", fontSize: 1, maxWidth: "48ch" }}>
          Something in the interface failed while drawing this view. Your repository and its history
          are unaffected — nothing here writes to them.
        </Text>
        <Box sx={{ display: "flex", gap: 2, mt: 2, flexWrap: "wrap", justifyContent: "center" }}>
          <Button onClick={() => this.setState({ error: null })}>Try again</Button>
          <Button variant="primary" onClick={() => window.location.assign("/")}>
            Go to the dashboard
          </Button>
        </Box>
      </Box>
    );
  }
}

export default ErrorBoundary;
