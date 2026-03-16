import { useState } from "react";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Box, Button, FormControl, TextInput, Flash, Link } from "@primer/react";

import AuthLayout from "./AuthLayout";
import { useAuth } from "../hooks/useAuth";
import { errorMessage } from "../services/api";

const Signup = () => {
  const { signup } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({ username: "", email: "", password: "" });
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const update = (field) => (event) =>
    setForm((current) => ({ ...current, [field]: event.target.value }));

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      await signup(form);
      navigate("/", { replace: true });
    } catch (caught) {
      setError(errorMessage(caught, "Could not create your account."));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Version control, built from first principles."
      footer={
        <>
          Already have an account? <Link as={RouterLink} to="/login">Sign in</Link>
        </>
      }
    >
      <Box as="form" onSubmit={handleSubmit} sx={{ display: "grid", gap: 3 }}>
        {error && <Flash variant="danger">{error}</Flash>}

        <FormControl required>
          <FormControl.Label>Username</FormControl.Label>
          <TextInput
            value={form.username}
            onChange={update("username")}
            autoComplete="username"
            autoFocus
            block
          />
          <FormControl.Caption>
            Letters, digits and single hyphens.
          </FormControl.Caption>
        </FormControl>

        <FormControl required>
          <FormControl.Label>Email address</FormControl.Label>
          <TextInput
            type="email"
            value={form.email}
            onChange={update("email")}
            autoComplete="email"
            block
          />
        </FormControl>

        <FormControl required>
          <FormControl.Label>Password</FormControl.Label>
          <TextInput
            type="password"
            value={form.password}
            onChange={update("password")}
            autoComplete="new-password"
            block
          />
          <FormControl.Caption>At least 8 characters.</FormControl.Caption>
        </FormControl>

        <Button type="submit" variant="primary" block disabled={submitting}>
          {submitting ? "Creating account…" : "Create account"}
        </Button>
      </Box>
    </AuthLayout>
  );
};

export default Signup;
