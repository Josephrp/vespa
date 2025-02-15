// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "distributor_stripe_component.h"
#include "distributormetricsset.h"
#include "messagetracker.h"
#include <vespa/storage/distributor/operations/cancel_scope.h>
#include <vespa/storageframework/generic/clock/timer.h>
#include <vespa/storageapi/messageapi/bucketinfocommand.h>
#include <vespa/storageapi/messageapi/bucketinforeply.h>

namespace storage::distributor {

struct PersistenceMessageTracker {
    virtual ~PersistenceMessageTracker() = default;
    using ToSend = MessageTracker::ToSend;

    virtual void cancel(const CancelScope& cancel_scope) = 0;
    virtual void fail(MessageSender&, const api::ReturnCode&) = 0;
    virtual void queueMessageBatch(std::vector<ToSend> messages) = 0;
    virtual uint16_t receiveReply(MessageSender&, api::BucketInfoReply&) = 0;
    virtual std::shared_ptr<api::BucketInfoReply>& getReply() = 0;
    virtual void updateFromReply(MessageSender&, api::BucketInfoReply&, uint16_t node) = 0;
    virtual void queueCommand(api::BucketCommand::SP, uint16_t target) = 0;
    virtual void flushQueue(MessageSender&) = 0;
    virtual uint16_t handleReply(api::BucketReply& reply) = 0;
    virtual void add_trace_tree_to_reply(vespalib::Trace trace) = 0;
};

class PersistenceMessageTrackerImpl final
    : public PersistenceMessageTracker,
      public MessageTracker
{
public:
    PersistenceMessageTrackerImpl(PersistenceOperationMetricSet& metric,
                                  std::shared_ptr<api::BucketInfoReply> reply,
                                  const DistributorNodeContext& node_ctx,
                                  DistributorStripeOperationContext& op_ctx,
                                  api::Timestamp revertTimestamp = 0);
    ~PersistenceMessageTrackerImpl() override;

    void cancel(const CancelScope& cancel_scope) override;

    void updateDB();
    void updateMetrics();
    [[nodiscard]] bool success() const noexcept { return _success; }
    void fail(MessageSender& sender, const api::ReturnCode& result) override;

    /**
       Returns the node the reply was from.
    */
    uint16_t receiveReply(MessageSender& sender, api::BucketInfoReply& reply) override;
    void updateFromReply(MessageSender& sender, api::BucketInfoReply& reply, uint16_t node) override;
    std::shared_ptr<api::BucketInfoReply>& getReply() override { return _reply; }

    using BucketNodePair = std::pair<document::Bucket, uint16_t>;

    void revert(MessageSender& sender, const std::vector<BucketNodePair>& revertNodes);

    /**
       Sends a set of messages that are permissible for early return.
       If early return is enabled, each message batch must be "finished", that is,
       have at most (messages.size() - initial redundancy) messages left in the
       queue and have it's first message be done.
    */
    void queueMessageBatch(std::vector<MessageTracker::ToSend> messages) override;

private:
    using MessageBatch  = std::vector<uint64_t>;
    using BucketInfoMap = std::map<document::Bucket, std::vector<BucketCopy>>;

    BucketInfoMap                         _remapBucketInfo;
    BucketInfoMap                         _bucketInfo;
    std::vector<MessageBatch>             _messageBatches;
    PersistenceOperationMetricSet&        _metric;
    std::shared_ptr<api::BucketInfoReply> _reply;
    DistributorStripeOperationContext&    _op_ctx;
    api::Timestamp                        _revertTimestamp;
    std::vector<BucketNodePair>           _revertNodes;
    mbus::Trace                           _trace;
    framework::MilliSecTimer              _requestTimer;
    CancelScope                           _cancel_scope;
    uint32_t                              _n_persistence_replies_total;
    uint32_t                              _n_successful_persistence_replies;
    uint8_t                               _priority;
    bool                                  _success;

    static void prune_cancelled_nodes_if_present(BucketInfoMap& bucket_and_replicas,
                                                 const CancelScope& cancel_scope);
    [[nodiscard]] bool canSendReplyEarly() const;
    void addBucketInfoFromReply(uint16_t node, const api::BucketInfoReply& reply);
    void logSuccessfulReply(uint16_t node, const api::BucketInfoReply& reply) const;
    [[nodiscard]] bool hasSentReply() const noexcept { return !_reply; }
    [[nodiscard]] bool shouldRevert() const;
    [[nodiscard]] bool has_majority_successful_replies() const noexcept;
    [[nodiscard]] bool has_minority_test_and_set_failure() const noexcept;
    void sendReply(MessageSender& sender);
    void updateFailureResult(const api::BucketInfoReply& reply);
    [[nodiscard]] bool node_is_effectively_cancelled(uint16_t node) const noexcept;
    void handleCreateBucketReply(api::BucketInfoReply& reply, uint16_t node);
    void handlePersistenceReply(api::BucketInfoReply& reply, uint16_t node);
    void transfer_trace_state_to_reply();

    void queueCommand(std::shared_ptr<api::BucketCommand> msg, uint16_t target) override {
        MessageTracker::queueCommand(std::move(msg), target);
    }
    void flushQueue(MessageSender& s) override { MessageTracker::flushQueue(s); }
    uint16_t handleReply(api::BucketReply& r) override { return MessageTracker::handleReply(r); }
    void add_trace_tree_to_reply(vespalib::Trace trace) override;
};

}
