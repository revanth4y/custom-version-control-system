import { Box, Spinner, Text, Heading, Button } from "@primer/react";
import Octicon from "./Octicon";
import { AlertIcon, InboxIcon, SyncIcon } from "@primer/octicons-react";

/**
 * The three states every data-driven page has to handle.
 *
 * Kept together because they are variations of one idea — the page has nothing
 * useful to show yet — and because sharing their proportions is what stops
 * loading, error and empty from each drifting into their own visual language.
 */

const Centered = ({ children, minHeight = "200px" }) => (
  <Box
    sx={{
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      textAlign: "center",
      gap: 2,
      minHeight,
      px: 3,
      py: 4,
    }}
  >
    {children}
  </Box>
);

export const LoadingState = ({ label = "Loading", minHeight }) => (
  <Centered minHeight={minHeight}>
    <Spinner size="medium" sx={{ color: "accent.fg" }} />
    <Text sx={{ color: "fg.muted", fontSize: 1 }}>{label}</Text>
  </Centered>
);

/**
 * A failure the user may be able to act on.
 *
 * Always offers a retry when one is available: most failures here are transient
 * network problems, and re-running the request is the obvious next step.
 */
export const ErrorState = ({ title = "Something went wrong", message, onRetry, minHeight }) => (
  <Centered minHeight={minHeight}>
    <Octicon icon={AlertIcon} size={24} sx={{ color: "danger.fg" }} />
    <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600, color: "fg.default" }}>
      {title}
    </Heading>
    {message && (
      <Text sx={{ color: "fg.muted", fontSize: 1, maxWidth: "420px" }}>{message}</Text>
    )}
    {onRetry && (
      <Button size="small" leadingVisual={SyncIcon} onClick={onRetry} sx={{ mt: 2 }}>
        Try again
      </Button>
    )}
  </Centered>
);

/**
 * Nothing to show, which is not a failure.
 *
 * Takes an action slot so an empty list can offer the thing that would fill it,
 * rather than leaving the user at a dead end.
 */
export const EmptyState = ({ icon = InboxIcon, title, message, action, minHeight }) => (
  <Centered minHeight={minHeight}>
    <Octicon icon={icon} size={24} sx={{ color: "fg.subtle" }} />
    <Heading as="h2" sx={{ fontSize: 2, fontWeight: 600, color: "fg.default" }}>
      {title}
    </Heading>
    {message && (
      <Text sx={{ color: "fg.muted", fontSize: 1, maxWidth: "420px" }}>{message}</Text>
    )}
    {action && <Box sx={{ mt: 2 }}>{action}</Box>}
  </Centered>
);

/**
 * Renders the right state for an async result, or the content when it arrives.
 *
 * Pages route their rendering through this so none of them can quietly forget a
 * state: handling all three is structural rather than a habit each page has to
 * remember.
 */
export const AsyncBoundary = ({
  loading,
  error,
  errorTitle,
  isEmpty,
  onRetry,
  loadingLabel,
  empty,
  minHeight,
  children,
}) => {
  if (loading) {
    return <LoadingState label={loadingLabel} minHeight={minHeight} />;
  }
  if (error) {
    // A page that knows what its failure means can say so: "No such user"
    // is more use than "Something went wrong" when the request 404s.
    return <ErrorState title={errorTitle} message={error} onRetry={onRetry} minHeight={minHeight} />;
  }
  if (isEmpty) {
    return empty ?? <EmptyState title="Nothing here yet" minHeight={minHeight} />;
  }
  return children;
};
