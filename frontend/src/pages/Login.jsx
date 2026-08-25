import { useState } from "react";
import { useNavigate } from "react-router-dom";
import RouterLink from "../components/common/RouterLink";
import { Box, Button, FormControl, TextInput, Flash, Link } from "@primer/react";

import AuthLayout from "./AuthLayout";
import { useAuth } from "../hooks/useAuth";
import { errorMessage } from "../services/api";

const Login = () => {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      await login({ email, password });
      navigate("/", { replace: true });
    } catch (caught) {
      // The API returns 401 without detail for bad credentials, so a specific
      // message here is more useful than the generic one.
      setError(
        caught?.response?.status === 401
          ? "Incorrect email or password."
          : errorMessage(caught, "Could not sign in."),
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Sign in to GitForge"
      footer={
        <>
          New here? <Link as={RouterLink} to="/signup">Create an account</Link>
        </>
      }
    >
      <Box as="form" onSubmit={handleSubmit} sx={{ display: "grid", gap: 3 }}>
        {error && <Flash variant="danger">{error}</Flash>}

        <FormControl required>
          <FormControl.Label>Email address</FormControl.Label>
          <TextInput
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            autoFocus
            block
          />
        </FormControl>

        <FormControl required>
          <FormControl.Label>Password</FormControl.Label>
          <TextInput
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            block
          />
        </FormControl>

        <Button type="submit" variant="primary" block disabled={submitting}>
          {submitting ? "Signing in…" : "Sign in"}
        </Button>
      </Box>
    </AuthLayout>
  );
};

export default Login;
