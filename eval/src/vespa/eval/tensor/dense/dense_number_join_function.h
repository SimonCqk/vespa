// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/eval/eval/tensor_function.h>

namespace vespalib::tensor {

/**
 * Tensor function for join operations between dense tensors and
 * numbers.
 **/
class DenseNumberJoinFunction : public eval::tensor_function::Join
{
public:
    enum class Primary : uint8_t { LHS, RHS };
    using join_fun_t = ::vespalib::eval::tensor_function::join_fun_t;
private:
    Primary _primary;
public:
    DenseNumberJoinFunction(const eval::ValueType &result_type,
                            const TensorFunction &lhs,
                            const TensorFunction &rhs,
                            join_fun_t function_in,
                            Primary primary_in);
    ~DenseNumberJoinFunction() override;
    Primary primary() const { return _primary; }
    bool inplace() const;
    eval::InterpretedFunction::Instruction compile_self(const eval::TensorEngine &engine, Stash &stash) const override;
    static const eval::TensorFunction &optimize(const eval::TensorFunction &expr, Stash &stash);
};

} // namespace vespalib::tensor
